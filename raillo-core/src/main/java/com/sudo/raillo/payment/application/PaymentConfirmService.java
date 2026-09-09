package com.sudo.raillo.payment.application;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.provided.PaymentConfirmer;
import com.sudo.raillo.payment.application.required.BookingCreator;
import com.sudo.raillo.payment.application.required.MemberFinder;
import com.sudo.raillo.payment.application.required.OrderReader;
import com.sudo.raillo.payment.application.required.PaymentGateway;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayConfirmResult;
import com.sudo.raillo.payment.application.required.PendingBookingReader;
import com.sudo.raillo.payment.application.required.SeatHoldReleaser;
import com.sudo.raillo.payment.application.required.TrainScheduleReader;
import com.sudo.raillo.payment.application.required.TrainSeatReader;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.exception.TossPaymentException;
import com.sudo.raillo.train.domain.ScheduleStop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 승인 유스케이스.
 *
 * <p>1. Order/Payment/PendingBooking/Member 조회 및 검증
 * <p>2. PaymentKey를 별도 트랜잭션으로 저장 후 게이트웨이 승인 API 호출
 * <p>3. 실패 시 실패 정보 저장 후 예외 전파
 * <p>4. 성공 시 Order 완료 → Booking 생성 → Payment 승인 → PendingBooking/Seat Hold 정리
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentConfirmService implements PaymentConfirmer {

	private final PaymentModifier paymentModifier;
	private final PaymentValidator paymentValidator;
	private final PaymentGateway paymentGateway;
	private final OrderReader orderReader;
	private final MemberFinder memberFinder;
	private final PendingBookingReader pendingBookingReader;
	private final BookingCreator bookingCreator;
	private final SeatHoldReleaser seatHoldReleaser;
	private final TrainScheduleReader trainScheduleReader;
	private final TrainSeatReader trainSeatReader;

	@Override
	public PaymentConfirmResult confirm(PaymentConfirmCommand command, String memberNo) {
		log.info("[결제 승인 시작] orderId={}, paymentKey={}, amount={}",
			command.orderId(), command.paymentKey(), command.amount());

		Order order = orderReader.getOrderByOrderCode(command.orderId());
		List<PendingBooking> pendingBookings = validateAndGetPendingBookings(order, memberNo);
		Member member = memberFinder.getMemberByMemberNo(memberNo);
		Payment payment = paymentModifier.getPaymentByOrder(order);

		orderReader.validateOrderOwner(order, member);
		paymentValidator.validatePaymentOwner(payment, member);
		paymentValidator.validateAmounts(command.amount(), order.getTotalAmount(), payment.getAmount());
		paymentValidator.validateDuplicatePayment(order);

		paymentModifier.updatePaymentKeyInNewTransaction(payment.getId(), command.paymentKey());
		// REQUIRES_NEW로 별도 커밋된 paymentKey를 바깥 트랜잭션 엔티티에도 동기화
		// (미동기화 시 바깥 트랜잭션 커밋 때 Hibernate가 paymentKey=null로 덮어씀)
		payment.updatePaymentKey(command.paymentKey());

		GatewayConfirmResult result;
		try {
			result = paymentGateway.confirm(command);
		} catch (TossPaymentException e) {
			paymentModifier.failPaymentInNewTransaction(payment.getId(), e.getErrorCode(), e.getMessage());
			log.info("[게이트웨이 결제 승인 실패] orderCode={}, httpStatus={}, code={}, message={}",
				command.orderId(), e.getHttpStatus(), e.getErrorCode(), e.getMessage());
			throw e;
		}

		paymentValidator.validateGatewayResponseMatchesRequest(result, command);

		order.completePayment();
		bookingCreator.createBookingFromOrder(order);
		payment.approve(result.method());
		cleanupPendingBookings(pendingBookings);

		log.info("[결제 승인 완료] paymentId={}, orderCode={}", payment.getId(), command.orderId());
		return PaymentConfirmResult.from(payment);
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
		List<String> pendingBookingIds = pendingBookings.stream()
			.map(PendingBooking::getId)
			.toList();
		String memberNo = pendingBookings.get(0).getMemberNo();

		try {
			pendingBookingReader.deletePendingBookings(pendingBookingIds, memberNo);
		} catch (Exception e) {
			// Payment는 이미 approve된 상태이므로 정리 실패로 트랜잭션을 롤백해선 안 된다.
			// 로그만 남기고 지나가면 PendingBooking과 Redis Hold는 TTL(seat-hold-architecture Lazy Cleanup)로 회수된다.
			// TODO(#254 후속): 승인 완료 이벤트 발행 + 아웃박스로 신뢰성 있는 정리 처리로 전환.
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
