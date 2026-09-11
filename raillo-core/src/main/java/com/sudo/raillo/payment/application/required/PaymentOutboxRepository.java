package com.sudo.raillo.payment.application.required;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.sudo.raillo.payment.domain.PaymentOutbox;

public interface PaymentOutboxRepository {

	PaymentOutbox save(PaymentOutbox outbox);

	Optional<PaymentOutbox> findById(Long id);

	Optional<PaymentOutbox> findByDeduplicationKey(String deduplicationKey);

	/**
	 * 처리 가능한 PENDING 항목을 우선순위 순(next_retry_at 오름차순, null 우선)으로 반환한다.
	 */
	List<PaymentOutbox> findProcessable(LocalDateTime now, int limit);
}
