package com.sudo.raillo.payment.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import lombok.RequiredArgsConstructor;

/**
 * 이미 시작된 attempt의 상태 전이를 별도 트랜잭션에서 커밋한다.
 *
 * <p>새 승인 시도 개시(lock, validate, save)는 {@link PaymentApprovalStarter}가 담당한다.
 * 이 클래스는 attempt 라이프사이클의 후속 상태 변경만 다룬다.
 */
@Component
@RequiredArgsConstructor
public class PaymentAttemptManager {

	private final PaymentAttemptRepository paymentAttemptRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailedInNewTransaction(Long attemptDbId, String errorCode, String errorMessage) {
		PaymentAttempt attempt = paymentAttemptRepository.findById(attemptDbId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_ATTEMPT_NOT_FOUND));
		attempt.markFailed(errorCode, errorMessage);
	}
}
