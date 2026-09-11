package com.sudo.raillo.payment.adapter.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import com.sudo.raillo.payment.domain.PaymentOutboxStatus;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentOutboxPersistenceAdapter implements PaymentOutboxRepository {

	private final PaymentOutboxJpaRepository jpaRepository;

	@Override
	public PaymentOutbox save(PaymentOutbox outbox) {
		return jpaRepository.save(outbox);
	}

	@Override
	public Optional<PaymentOutbox> findById(Long id) {
		return jpaRepository.findById(id);
	}

	@Override
	public Optional<PaymentOutbox> findByDeduplicationKey(String deduplicationKey) {
		return jpaRepository.findByDeduplicationKey(deduplicationKey);
	}

	@Override
	public List<PaymentOutbox> findProcessable(LocalDateTime now, int limit) {
		return jpaRepository.findProcessable(PaymentOutboxStatus.PENDING, now, Limit.of(limit));
	}
}
