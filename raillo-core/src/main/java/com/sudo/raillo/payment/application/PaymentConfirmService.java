package com.sudo.raillo.payment.application;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.application.provided.PaymentConfirmer;
import com.sudo.raillo.payment.application.required.PaymentGateway;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayConfirmResult;
import com.sudo.raillo.payment.application.required.PendingBookingReader;
import com.sudo.raillo.payment.application.required.SeatHoldReleaser;
import com.sudo.raillo.payment.application.required.TrainScheduleReader;
import com.sudo.raillo.payment.application.required.TrainSeatReader;
import com.sudo.raillo.payment.application.result.PaymentConfirmResult;
import com.sudo.raillo.payment.domain.exception.TossPaymentException;
import com.sudo.raillo.train.domain.ScheduleStop;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제 승인 유스케이스. 트랜잭션을 열지 않고 아래 세 단계를 순서대로 연결한다.
 *
 * <p>1. TX A — {@link PaymentApprovalStarter}: 조회·검증 후 IN_PROGRESS attempt를 커밋한다.
 * <p>2. Toss 승인 요청 — DB 트랜잭션 밖에서 호출한다. 확정 실패(4xx)만 FAILED로 마킹하고,
 * 결과 불명(5xx·타임아웃)은 IN_PROGRESS로 남겨 Recovery 대상으로 둔다.
 * <p>3. TX B — {@link PaymentApprovalFinalizer}: Order/Booking/Payment/Attempt/Outbox를 함께 커밋한다.
 *
 * <p>PendingBooking/Seat Hold 정리는 TX B 커밋 이후에 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentConfirmService implements PaymentConfirmer {

	private final PaymentApprovalStarter paymentApprovalStarter;
	private final PaymentApprovalFinalizer paymentApprovalFinalizer;
	private final PaymentAttemptManager paymentAttemptManager;
	private final PaymentModifier paymentModifier;
	private final PaymentGateway paymentGateway;
	private final PendingBookingReader pendingBookingReader;
	private final SeatHoldReleaser seatHoldReleaser;
	private final TrainScheduleReader trainScheduleReader;
	private final TrainSeatReader trainSeatReader;

	@Override
	public PaymentConfirmResult confirm(PaymentConfirmCommand command, String memberNo) {
		// 1. TX A - 승인 시작
		PaymentApprovalStart start = paymentApprovalStarter.start(command, memberNo);
		if (start.isAlreadyConfirmed()) {
			return start.previousResult();
		}

		// 2. Toss 승인 요청 (DB 트랜잭션 없음)
		GatewayConfirmResult approval = requestTossApproval(command, start.paymentId(), start.attemptDbId());

		// 3. TX B - 승인 확정
		PaymentConfirmResult result = paymentApprovalFinalizer.finalizeApproval(
			start.paymentId(), start.attemptDbId(), command, approval, start.pendingBookings()
		);

		// TX B 커밋 이후 실행한다. 후속 PR에서 OutboxWorker로 완전히 이관한다.
		cleanupPendingBookings(start.pendingBookings());

		log.info("[결제 승인 완료] paymentId={}, orderCode={}", start.paymentId(), command.orderId());
		return result;
	}

	private GatewayConfirmResult requestTossApproval(PaymentConfirmCommand command, Long paymentId, Long attemptDbId) {
		// TODO(#257 Task 10): 응답 유실·타임아웃은 IN_PROGRESS로 유지하고 Recovery Worker가 Toss 조회로 확정한다.
		// Toss 승인 후 TX B가 실패한 경우도 같은 attempt를 복구하며, 승인 API를 재호출하지 않는다.
		try {
			return paymentGateway.confirm(command);
		} catch (TossPaymentException e) {
			handleGatewayFailure(paymentId, attemptDbId, command, e);
			throw e;
		}
	}

	private void handleGatewayFailure(
		Long paymentId,
		Long attemptDbId,
		PaymentConfirmCommand command,
		TossPaymentException exception
	) {
		if (!exception.isDefinitiveFailure()) {
			log.warn("[게이트웨이 결제 승인 결과 불명] IN_PROGRESS 유지: orderCode={}, httpStatus={}, code={}",
				command.orderId(), exception.getHttpStatus(), exception.getErrorCode());
			return;
		}

		paymentAttemptManager.markFailedInNewTransaction(
			attemptDbId, exception.getErrorCode(), exception.getMessage()
		);
		paymentModifier.failPaymentInNewTransaction(
			paymentId, exception.getErrorCode(), exception.getMessage()
		);
		log.info("[게이트웨이 결제 승인 확정 실패] orderCode={}, httpStatus={}, code={}, message={}",
			command.orderId(), exception.getHttpStatus(), exception.getErrorCode(), exception.getMessage());
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
			// Payment는 이미 approve된 상태이므로 정리 실패로 승인 결과를 되돌려선 안 된다.
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
