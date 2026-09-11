package com.sudo.raillo.payment.application;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import lombok.RequiredArgsConstructor;

/**
 * 승인 시도 시작을 안전하게 개시하는 컴포넌트.
 *
 * <p>같은 payment에 대한 동시 승인 요청과 이전 시도의 잔재를 모두 배제하기 위해
 * Payment에 pessimistic lock을 걸고 attempt 저장과 paymentKey 반영을 하나의 짧은
 * REQUIRES_NEW 트랜잭션에서 처리한다. 외부 Toss 호출은 이 트랜잭션 밖(호출자)에서 수행된다.
 *
 * <p>attempt 상태 전이(markFailed 등)는 {@link PaymentAttemptManager}가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class PaymentApprovalStarter {

	private final PaymentAttemptRepository paymentAttemptRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentValidator paymentValidator;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PaymentAttemptStartResult startApprovalInNewTransaction(Long paymentId, String attemptId, String paymentKey) {
		// 잠금은 이 짧은 트랜잭션 안에서만 유지되고 Toss 호출 전에 해제된다.
		Payment payment = paymentRepository.findByIdForUpdate(paymentId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));

		Optional<PaymentAttempt> existing = paymentAttemptRepository.findByAttemptId(attemptId);
		if (existing.isPresent()) {
			paymentValidator.validateApprovalAttempt(existing.get(), paymentId, paymentKey);
			return new PaymentAttemptStartResult(existing.get().getId(), false);
		}

		paymentValidator.validateApprovable(payment);
		paymentAttemptRepository.findLatestApprovalByPaymentId(paymentId).ifPresent(previous -> {
			// attempt 실패와 Payment.fail이 별도 커밋되는 짧은 간격에도 새 승인이 끼어들 수 없다.
			PaymentError error = switch (previous.getStatus()) {
				case IN_PROGRESS -> PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS;
				case FAILED -> PaymentError.PAYMENT_ATTEMPT_ALREADY_FAILED;
				case SUCCEEDED -> PaymentError.PAYMENT_ALREADY_COMPLETED;
			};
			throw new BusinessException(error);
		});

		// attempt 저장이 실패하면 paymentKey 변경도 함께 롤백된다.
		payment.updatePaymentKey(paymentKey);
		PaymentAttempt attempt = PaymentAttempt.startApproval(paymentId, attemptId, paymentKey);
		return new PaymentAttemptStartResult(paymentAttemptRepository.save(attempt).getId(), true);
	}
}
