package com.sudo.raillo.payment.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.provided.PaymentConfirmer;
import com.sudo.raillo.payment.application.required.BookingCreator;
import com.sudo.raillo.payment.application.required.MemberFinder;
import com.sudo.raillo.payment.application.required.OrderReader;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.required.PaymentGateway;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayConfirmResult;
import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.application.required.PendingBookingReader;
import com.sudo.raillo.payment.application.required.SeatHoldReleaser;
import com.sudo.raillo.payment.application.required.TrainScheduleReader;
import com.sudo.raillo.payment.application.required.TrainSeatReader;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.payment.domain.exception.TossPaymentException;
import com.sudo.raillo.train.domain.ScheduleStop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 승인 유스케이스.
 *
 * <p>1. Order/Payment/PendingBooking/Member 조회 및 검증
 * <p>2. Payment 잠금 아래 PaymentKey와 IN_PROGRESS attempt를 함께 저장 (REQUIRES_NEW)
 * <p>3. 새 attempt를 생성한 호출만 게이트웨이 승인 API 호출
 * <p>4. 실패 시 attempt와 payment를 FAILED로 마킹 후 예외 전파
 * <p>5. 성공 시 Order 완료 → Booking 생성 → Payment 승인 → PendingBooking/Seat Hold 정리
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentConfirmService implements PaymentConfirmer {

	private final PaymentModifier paymentModifier;
	private final PaymentValidator paymentValidator;
	private final PaymentAttemptManager paymentAttemptManager;
	private final PaymentAttemptRepository paymentAttemptRepository;
	private final PaymentOutboxRepository paymentOutboxRepository;
	private final PaymentGateway paymentGateway;
	private final OrderReader orderReader;
	private final MemberFinder memberFinder;
	private final PendingBookingReader pendingBookingReader;
	private final BookingCreator bookingCreator;
	private final SeatHoldReleaser seatHoldReleaser;
	private final TrainScheduleReader trainScheduleReader;
	private final TrainSeatReader trainSeatReader;
	private final ObjectMapper objectMapper;

	@Override
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PaymentConfirmResult confirm(PaymentConfirmCommand command, String memberNo) {
		// MySQL의 REPEATABLE_READ에서는 첫 일반 조회(Order 조회) 시점의 스냅샷을 다른 테이블 조회에도 사용한다.
		// 따라서 이후 PaymentAttemptManager가 REQUIRES_NEW로 저장·커밋한 attempt는 findById로 조회되지 않는다.
		// 일반 조회마다 새 스냅샷을 사용하는 READ_COMMITTED로 설정해, 별도 트랜잭션에서 커밋한 attempt를 읽는다.
		String attemptId = command.attemptIdOrDerived();
		paymentValidator.validateAttemptId(attemptId);
		log.info("[결제 승인 시작] orderId={}, paymentKey={}, amount={}, attemptId={}",
			command.orderId(), command.paymentKey(), command.amount(), attemptId);

		Order order = orderReader.getOrderByOrderCode(command.orderId());
		Member member = memberFinder.getMemberByMemberNo(memberNo);
		Payment payment = paymentModifier.getPaymentByOrder(order);

		orderReader.validateOrderOwner(order, member);
		paymentValidator.validatePaymentOwner(payment, member);
		paymentValidator.validateAmounts(command.amount(), order.getTotalAmount(), payment.getAmount());

		// 같은 attemptId로 재요청이 왔다면 상태에 따라 이전 결과를 반환하거나 예외를 던지고 조기 종료한다.
		// PendingBooking 조회보다 먼저 처리해야 SUCCEEDED 재요청도 정상 응답한다.
		// (성공한 flow에서는 PendingBooking이 이미 정리됐을 수 있어 재조회 시 만료 예외가 난다.)
		Optional<PaymentAttempt> existingAttempt = paymentAttemptRepository.findByAttemptId(attemptId);
		if (existingAttempt.isPresent()) {
			return handleExistingAttempt(existingAttempt.get(), payment, command);
		}

		paymentValidator.validateApprovable(payment);
		// TODO(#257 Task 8, 12): 인라인 cleanup 제거 후, 이 조회 직전 다른 요청이 승인을 확정하는 경로도 검증한다.
		List<PendingBooking> pendingBookings = validateAndGetPendingBookings(order, memberNo);
		paymentValidator.validateDuplicatePayment(order);

		PaymentAttemptStartResult started;
		try {
			started = paymentAttemptManager.startApprovalInNewTransaction(
				payment.getId(), attemptId, command.paymentKey()
			);
		} catch (DataIntegrityViolationException e) {
			// 별도 트랜잭션 롤백 후 실제로 같은 attempt가 생겼는지 확인한다. 다른 무결성 오류는 그대로 전파한다.
			PaymentAttempt conflicting = paymentAttemptRepository.findByAttemptId(attemptId).orElseThrow(() -> e);
			return handleExistingAttempt(conflicting, payment, command);
		}
		if (!started.created()) {
			PaymentAttempt existing = paymentAttemptRepository.findById(started.attemptDbId())
				.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_ATTEMPT_NOT_FOUND));
			return handleExistingAttempt(existing, payment, command);
		}
		Long attemptDbId = started.attemptDbId();
		// 별도 커밋된 paymentKey를 바깥 엔티티에도 반영해, 최종 UPDATE가 이전 값으로 덮어쓰지 않도록 한다.
		payment.updatePaymentKey(command.paymentKey());

		GatewayConfirmResult result;
		// TODO(#257 Task 10): 응답 유실·타임아웃은 IN_PROGRESS로 유지하고 Recovery Worker가 Toss 조회로 확정한다.
		// Toss 승인 후 아래 DB 확정/커밋이 실패한 경우도 같은 attempt를 복구하며, 승인 API를 재호출하지 않는다.
		try {
			result = paymentGateway.confirm(command);
		} catch (TossPaymentException e) {
			paymentAttemptManager.markFailedInNewTransaction(attemptDbId, e.getErrorCode(), e.getMessage());
			paymentModifier.failPaymentInNewTransaction(payment.getId(), e.getErrorCode(), e.getMessage());
			log.info("[게이트웨이 결제 승인 실패] orderCode={}, httpStatus={}, code={}, message={}",
				command.orderId(), e.getHttpStatus(), e.getErrorCode(), e.getMessage());
			throw e;
		}

		paymentValidator.validateGatewayResponseMatchesRequest(result, command);

		order.completePayment();
		bookingCreator.createBookingFromOrder(order);
		payment.approve(result.method());

		PaymentAttempt attempt = paymentAttemptRepository.findById(attemptDbId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_ATTEMPT_NOT_FOUND));
		attempt.markSucceeded();

		paymentOutboxRepository.save(buildBookingConfirmedOutbox(payment, pendingBookings));

		cleanupPendingBookings(pendingBookings);

		log.info("[결제 승인 완료] paymentId={}, orderCode={}", payment.getId(), command.orderId());
		return PaymentConfirmResult.from(payment);
	}

	private PaymentConfirmResult handleExistingAttempt(PaymentAttempt existing, Payment payment, PaymentConfirmCommand command) {
		paymentValidator.validateApprovalAttempt(existing, payment.getId(), command.paymentKey());
		return switch (existing.getStatus()) {
			case SUCCEEDED -> {
				log.info("[결제 재요청 - SUCCEEDED attempt 재사용] attemptId={}, paymentId={}",
					existing.getAttemptId(), payment.getId());
				// READ_COMMITTED여도 이미 읽은 Payment 객체는 갱신되지 않으므로 DB에서 결과를 직접 조회한다.
				yield paymentModifier.getConfirmResult(payment.getId());
			}
			case FAILED -> throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_ALREADY_FAILED);
			// TODO(#257 Task 10, 12): 오래된 IN_PROGRESS의 대사/롤포워드/보상과 복구 후 재요청을 통합 검증한다.
			case IN_PROGRESS -> throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS);
		};
	}

	private PaymentOutbox buildBookingConfirmedOutbox(Payment payment, List<PendingBooking> pendingBookings) {
		String dedupKey = "payment:%d:booking-confirmed".formatted(payment.getId());
		BookingConfirmedPayload payload = BookingConfirmedPayload.from(pendingBookings);
		try {
			String payloadJson = objectMapper.writeValueAsString(payload);
			return PaymentOutbox.forBookingConfirmed(payment.getId(), dedupKey, payloadJson);
		} catch (JacksonException e) {
			throw new BusinessException(PaymentError.PAYMENT_OUTBOX_PAYLOAD_SERIALIZATION_FAILED);
		}
	}

	private List<PendingBooking> validateAndGetPendingBookings(Order order, String memberNo) {
		List<String> pendingBookingIds = orderReader.getPendingBookingIds(order);
		if (pendingBookingIds.isEmpty()) {
			log.error("[PendingBooking 검증 실패] pendingBookingIds가 없음: orderCode={}", order.getOrderCode());
			throw new BusinessException(BookingError.PENDING_BOOKING_IDS_REQUIRED);
		}
		return pendingBookingReader.getPendingBookings(pendingBookingIds, memberNo);
	}

	private void cleanupPendingBookings(List<PendingBooking> pendingBookings) {
		// TODO(#257 Task 8, 9): 이 메서드와 호출을 제거하고 outbox 처리·재시도·최대 시도 초과 처리를 Worker로 옮긴다.
		// PR 2의 처리기는 NoOp이며, 실제 Redis 정리는 새 스키마 확정 후 별도 이슈에서 구현한다.
		List<String> pendingBookingIds = pendingBookings.stream()
			.map(PendingBooking::getId)
			.toList();
		String memberNo = pendingBookings.get(0).getMemberNo();

		try {
			pendingBookingReader.deletePendingBookings(pendingBookingIds, memberNo);
		} catch (Exception e) {
			// Payment는 이미 approve된 상태이므로 정리 실패로 트랜잭션을 롤백해선 안 된다.
			// 로그만 남기고 지나가면 PendingBooking과 Redis Hold는 TTL(seat-hold-architecture Lazy Cleanup)로 회수된다.
			// TODO(#257 Task 8): 위 인라인 정리 전체를 outbox 처리로 이관한다.
			log.error("[PendingBooking 삭제 실패] error={}", e.getMessage(), e);
		}

		List<Long> allStopIds = pendingBookings.stream()
			.flatMap(pb -> Stream.of(pb.getDepartureStopId(), pb.getArrivalStopId()))
			.toList();

		Map<Long, ScheduleStop> stopMap = trainScheduleReader.getScheduleStops(allStopIds).stream()
			.collect(Collectors.toMap(ScheduleStop::getId, Function.identity()));

		pendingBookings.forEach(pb -> {
			List<Long> seatIds = pb.getSeatIds();
			Long trainCarId = trainSeatReader.getTrainCarId(seatIds);
			ScheduleStop departureStop = stopMap.get(pb.getDepartureStopId());
			ScheduleStop arrivalStop = stopMap.get(pb.getArrivalStopId());

			seatHoldReleaser.releaseSeats(
				pb.getId(),
				pb.getTrainScheduleId(),
				seatIds,
				trainCarId,
				departureStop.getStopOrder(),
				arrivalStop.getStopOrder()
			);
		});

		log.info("[PendingBooking 삭제 및 Hold 해제 완료] pendingBookingCount={}", pendingBookings.size());
	}
}
