package com.sudo.raillo.payment.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import com.sudo.raillo.support.annotation.ServiceTest;

@ServiceTest
class PaymentOutboxPersistenceAdapterTest {

	@Autowired
	private PaymentOutboxRepository paymentOutboxRepository;

	@Test
	@DisplayName("저장한 outbox를 deduplication key로 조회한다")
	void save_and_findByDedup() {
		PaymentOutbox saved = paymentOutboxRepository.save(
			PaymentOutbox.forBookingConfirmed(100L, "payment:100:booking-confirmed", "{}")
		);

		var found = paymentOutboxRepository.findByDeduplicationKey("payment:100:booking-confirmed");

		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(saved.getId());
	}

	@Test
	@DisplayName("next_retry_at이 null 또는 now 이하인 PENDING만 반환한다")
	void findProcessable_filters() {
		PaymentOutbox immediate = paymentOutboxRepository.save(
			PaymentOutbox.forBookingConfirmed(1L, "k-1", "{}")
		);
		PaymentOutbox later = paymentOutboxRepository.save(
			PaymentOutbox.forBookingConfirmed(2L, "k-2", "{}")
		);
		later.markRetry(LocalDateTime.now().plusHours(1));
		paymentOutboxRepository.save(later);

		List<PaymentOutbox> results = paymentOutboxRepository.findProcessable(LocalDateTime.now(), 10);

		assertThat(results).extracting(PaymentOutbox::getId).containsExactly(immediate.getId());
	}
}
