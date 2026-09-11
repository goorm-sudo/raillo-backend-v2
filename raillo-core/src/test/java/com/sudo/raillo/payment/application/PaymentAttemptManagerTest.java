package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.OrderFixture;

@ServiceTest
class PaymentAttemptManagerTest {

	@Autowired private PaymentAttemptManager paymentAttemptManager;
	@Autowired private PaymentAttemptRepository paymentAttemptRepository;
	@Autowired private PaymentRepository paymentRepository;
	@Autowired private MemberRepository memberRepository;
	@Autowired private OrderRepository orderRepository;

	private Payment payment;

	@BeforeEach
	void setUp() {
		var member = memberRepository.save(MemberFixture.create());
		var order = orderRepository.save(OrderFixture.create(member));
		payment = paymentRepository.save(Payment.create(member, order));
	}

	@Test
	@DisplayName("markFailedInNewTransaction으로 attempt를 FAILED로 전환하고 에러 정보를 기록한다")
	void markFailedInNewTransaction_transitions() {
		// given: IN_PROGRESS attempt를 직접 저장
		PaymentAttempt inProgress = paymentAttemptRepository.save(
			PaymentAttempt.startApproval(payment.getId(), "attempt-abc", "toss-key"));

		// when
		paymentAttemptManager.markFailedInNewTransaction(inProgress.getId(), "REJECT", "카드 거절");

		// then
		PaymentAttempt updated = paymentAttemptRepository.findById(inProgress.getId()).orElseThrow();
		assertThat(updated.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(updated.getErrorCode()).isEqualTo("REJECT");
		assertThat(updated.getErrorMessage()).isEqualTo("카드 거절");
	}
}
