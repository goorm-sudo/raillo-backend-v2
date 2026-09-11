package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.support.annotation.ServiceTest;

@ServiceTest
class PaymentAttemptManagerTest {

	@Autowired
	private PaymentAttemptManager paymentAttemptManager;

	@Autowired
	private PaymentAttemptRepository paymentAttemptRepository;

	@Test
	@DisplayName("startApprovalInNewTransaction으로 IN_PROGRESS attempt를 저장하고 id를 반환한다")
	void startApprovalInNewTransaction_persists() {
		Long attemptDbId = paymentAttemptManager.startApprovalInNewTransaction(1L, "attempt-abc", "toss-key");

		PaymentAttempt saved = paymentAttemptRepository.findById(attemptDbId).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
		assertThat(saved.getAttemptId()).isEqualTo("attempt-abc");
	}

	@Test
	@DisplayName("markFailedInNewTransaction으로 attempt를 FAILED로 전환한다")
	void markFailedInNewTransaction_transitions() {
		Long attemptDbId = paymentAttemptManager.startApprovalInNewTransaction(1L, "attempt-abc", "toss-key");

		paymentAttemptManager.markFailedInNewTransaction(attemptDbId, "REJECT", "카드 거절");

		PaymentAttempt updated = paymentAttemptRepository.findById(attemptDbId).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(updated.getErrorCode()).isEqualTo("REJECT");
	}
}
