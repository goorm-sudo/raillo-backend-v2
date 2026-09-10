package com.sudo.raillo.payment.application.required;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptType;

public interface PaymentAttemptRepository {

	PaymentAttempt save(PaymentAttempt attempt);

	Optional<PaymentAttempt> findById(Long id);

	Optional<PaymentAttempt> findByAttemptId(String attemptId);

	/**
	 * Recovery Worker가 처리할 후보를 조회한다.
	 * IN_PROGRESS 상태이면서 updated_at이 임계값보다 오래된 attempt를 반환한다.
	 */
	List<PaymentAttempt> findInProgressOlderThan(PaymentAttemptType type, LocalDateTime threshold, int limit);
}
