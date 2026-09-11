package com.sudo.raillo.payment.application;

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

@Component
@RequiredArgsConstructor
public class PaymentAttemptManager {

	private final PaymentAttemptRepository paymentAttemptRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentValidator paymentValidator;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PaymentAttemptStartResult startApprovalInNewTransaction(Long paymentId, String attemptId, String paymentKey) {
		// 잠금은 이 짧은 트랜잭션 안에서만 유지하고 Toss 호출 전에 해제한다.
		Payment payment = paymentRepository.findByIdForUpdate(paymentId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));
		var existing = paymentAttemptRepository.findByAttemptId(attemptId);
		if (existing.isPresent()) {
			paymentValidator.validateApprovalAttempt(existing.get(), paymentId, paymentKey);
			return new PaymentAttemptStartResult(existing.get().getId(), false);
		}

		paymentValidator.validateApprovable(payment);
		paymentAttemptRepository.findLatestApprovalByPaymentId(paymentId).ifPresent(previous -> {
			// attempt 실패와 Payment 실패가 별도 커밋되는 간격에도 새 승인이 끼어들 수 없다.
			PaymentError error = switch (previous.getStatus()) {
				case IN_PROGRESS -> PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS;
				case FAILED -> PaymentError.PAYMENT_ATTEMPT_ALREADY_FAILED;
				case SUCCEEDED -> PaymentError.PAYMENT_ALREADY_COMPLETED;
			};
			throw new BusinessException(error);
		});

		// attempt 저장 실패 시 paymentKey 변경도 함께 롤백한다.
		payment.updatePaymentKey(paymentKey);
		PaymentAttempt attempt = PaymentAttempt.startApproval(paymentId, attemptId, paymentKey);
		return new PaymentAttemptStartResult(paymentAttemptRepository.save(attempt).getId(), true);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailedInNewTransaction(Long attemptDbId, String errorCode, String errorMessage) {
		PaymentAttempt attempt = paymentAttemptRepository.findById(attemptDbId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_ATTEMPT_NOT_FOUND));
		attempt.markFailed(errorCode, errorMessage);
	}
}
