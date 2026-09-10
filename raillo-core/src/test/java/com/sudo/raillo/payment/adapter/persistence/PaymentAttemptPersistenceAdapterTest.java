package com.sudo.raillo.payment.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptType;
import com.sudo.raillo.support.annotation.ServiceTest;

@ServiceTest
class PaymentAttemptPersistenceAdapterTest {

	@Autowired
	private PaymentAttemptRepository paymentAttemptRepository;

	@Test
	@DisplayName("저장한 attempt를 attemptId로 조회한다")
	void save_and_findByAttemptId() {
		PaymentAttempt saved = paymentAttemptRepository.save(
			PaymentAttempt.startApproval(1L, "attempt-abc", "toss-key")
		);

		var found = paymentAttemptRepository.findByAttemptId("attempt-abc");

		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(saved.getId());
		assertThat(found.get().getStatus()).isEqualTo(saved.getStatus());
	}

	@Test
	@DisplayName("threshold보다 오래된 IN_PROGRESS attempt만 반환한다")
	void findInProgressOlderThan_filtersByThreshold() {
		paymentAttemptRepository.save(
			PaymentAttempt.startApproval(1L, "attempt-fresh", "key-fresh")
		);
		paymentAttemptRepository.save(
			PaymentAttempt.startApproval(2L, "attempt-stale", "key-stale")
		);

		LocalDateTime futureThreshold = LocalDateTime.now().plusDays(1);
		List<PaymentAttempt> results =
			paymentAttemptRepository.findInProgressOlderThan(PaymentAttemptType.APPROVAL, futureThreshold, 10);

		assertThat(results).hasSize(2);
		assertThat(results).extracting(PaymentAttempt::getAttemptId)
			.containsExactlyInAnyOrder("attempt-fresh", "attempt-stale");
	}
}
