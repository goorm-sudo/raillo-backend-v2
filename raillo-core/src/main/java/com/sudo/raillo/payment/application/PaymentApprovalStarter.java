package com.sudo.raillo.payment.application;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.application.required.MemberFinder;
import com.sudo.raillo.payment.application.required.OrderReader;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.result.PaymentAttemptStartResult;
import com.sudo.raillo.payment.application.required.PendingBookingReader;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 승인 시작 단계. Toss 호출에 필요한 조회·검증을 마치고 {@link PaymentAttemptManager}로 TX A를 커밋한다.
 *
 * <p>자체 트랜잭션을 열지 않는다. 조회는 각 컴포넌트의 짧은 트랜잭션에서 수행되고,
 * PendingBooking(Redis) 조회도 DB 트랜잭션 밖에 둔다. 커밋 지점은 TX A 하나뿐이다.
 *
 * <p>같은 attemptId 재요청은 Toss를 호출하지 않고 이전 결과를 반환하거나 예외를 던진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentApprovalStarter {

	private final PaymentAttemptManager paymentAttemptManager;
	private final PaymentAttemptRepository paymentAttemptRepository;
	private final PaymentReader paymentReader;
	private final PaymentValidator paymentValidator;
	private final OrderReader orderReader;
	private final MemberFinder memberFinder;
	private final PendingBookingReader pendingBookingReader;

	public PaymentApprovalStart start(PaymentConfirmCommand command, String memberNo) {
		String attemptId = command.attemptIdOrDerived();
		paymentValidator.validateAttemptId(attemptId);
		log.info("[결제 승인 시작] orderId={}, paymentKey={}, amount={}, attemptId={}",
			command.orderId(), command.paymentKey(), command.amount(), attemptId);

		Order order = orderReader.getOrderByOrderCode(command.orderId());
		Member member = memberFinder.getMemberByMemberNo(memberNo);
		Payment payment = paymentReader.getPaymentByOrder(order);

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
		List<PendingBooking> pendingBookings = getPendingBookings(order, memberNo);
		paymentValidator.validateDuplicatePayment(order);

		PaymentAttemptStartResult registered;
		try {
			registered = paymentAttemptManager.startApprovalInNewTransaction(
				payment.getId(), attemptId, command.paymentKey()
			);
		} catch (DataIntegrityViolationException e) {
			// TX A 롤백 후 실제로 같은 attempt가 생겼는지 확인한다. 다른 무결성 오류는 그대로 전파한다.
			PaymentAttempt conflicting = paymentAttemptRepository.findByAttemptId(attemptId).orElseThrow(() -> e);
			return handleExistingAttempt(conflicting, payment, command);
		}

		if (!registered.created()) {
			PaymentAttempt existing = paymentAttemptRepository.findById(registered.attemptDbId())
				.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_ATTEMPT_NOT_FOUND));
			return handleExistingAttempt(existing, payment, command);
		}

		return PaymentApprovalStart.started(payment.getId(), registered.attemptDbId(), pendingBookings);
	}

	private PaymentApprovalStart handleExistingAttempt(
		PaymentAttempt existing,
		Payment payment,
		PaymentConfirmCommand command
	) {
		paymentValidator.validateApprovalAttempt(existing, payment.getId(), command.paymentKey());
		return switch (existing.getStatus()) {
			case SUCCEEDED -> {
				log.info("[결제 재요청 - SUCCEEDED attempt 재사용] attemptId={}, paymentId={}",
					existing.getAttemptId(), payment.getId());
				// 별도 읽기 트랜잭션에서 최신 커밋 결과를 조회한다.
				yield PaymentApprovalStart.alreadyConfirmed(paymentReader.getConfirmResult(payment.getId()));
			}
			case FAILED -> throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_ALREADY_FAILED);
			// TODO(#257 Task 10, 12): 오래된 IN_PROGRESS의 대사/롤포워드/보상과 복구 후 재요청을 통합 검증한다.
			case IN_PROGRESS -> throw new BusinessException(PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS);
		};
	}

	private List<PendingBooking> getPendingBookings(Order order, String memberNo) {
		List<String> pendingBookingIds = orderReader.getPendingBookingIds(order);
		if (pendingBookingIds.isEmpty()) {
			log.error("[PendingBooking 검증 실패] pendingBookingIds가 없음: orderCode={}", order.getOrderCode());
			throw new BusinessException(BookingError.PENDING_BOOKING_IDS_REQUIRED);
		}
		return pendingBookingReader.getPendingBookings(pendingBookingIds, memberNo);
	}
}
