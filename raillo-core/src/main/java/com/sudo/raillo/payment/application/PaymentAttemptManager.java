package com.sudo.raillo.payment.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentAttemptManager {

	private final PaymentAttemptRepository paymentAttemptRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Long startApprovalInNewTransaction(Long paymentId, String attemptId, String paymentKey) {
		PaymentAttempt attempt = PaymentAttempt.startApproval(paymentId, attemptId, paymentKey);
		return paymentAttemptRepository.save(attempt).getId();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailedInNewTransaction(Long attemptDbId, String errorCode, String errorMessage) {
		PaymentAttempt attempt = paymentAttemptRepository.findById(attemptDbId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_ATTEMPT_NOT_FOUND));
		attempt.markFailed(errorCode, errorMessage);
	}
}
