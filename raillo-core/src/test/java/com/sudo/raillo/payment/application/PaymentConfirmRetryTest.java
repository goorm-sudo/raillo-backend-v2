package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient;
import com.sudo.raillo.payment.application.provided.PaymentConfirmer;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.payment.domain.PaymentStatus;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.OrderTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;

@ServiceTest
class PaymentConfirmRetryTest {

	@Autowired private PaymentConfirmer paymentConfirmer;
	@Autowired private PaymentRepository paymentRepository;
	@Autowired private PaymentAttemptRepository attemptRepository;
	@Autowired private MemberRepository memberRepository;
	@Autowired private OrderTestHelper orderTestHelper;
	@Autowired private TrainTestHelper trainTestHelper;
	@Autowired private TrainScheduleTestHelper scheduleTestHelper;
	@Autowired private JdbcTemplate jdbcTemplate;

	@MockitoBean private TossPaymentClient tossPaymentClient;

	private Member member;
	private Order order;
	private Payment payment;
	private TrainScheduleResult schedule;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		schedule = scheduleTestHelper.createDefault(trainTestHelper.createKTX());
		order = orderTestHelper.createDefault(member, schedule).order();
		payment = paymentRepository.save(Payment.create(member, order));
	}

	@ParameterizedTest
	@EnumSource(PaymentAttemptStatus.class)
	@DisplayName("다른 결제의 attemptId를 재사용하면 상태와 관계없이 요청 불일치로 거절한다")
	void rejects_attempt_from_another_payment(PaymentAttemptStatus status) {
		// given
		Order otherOrder = orderTestHelper.createDefault(member, schedule).order();
		Payment otherPayment = paymentRepository.save(Payment.create(member, otherOrder));
		saveAttempt(otherPayment.getId(), status);

		// when / then
		assertRequestMismatch(command("original-key"));
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getPaymentStatus())
			.isEqualTo(PaymentStatus.PENDING);
	}

	@ParameterizedTest
	@EnumSource(PaymentAttemptStatus.class)
	@DisplayName("같은 attemptId에 다른 paymentKey를 보내면 상태와 관계없이 요청 불일치로 거절한다")
	void rejects_changed_payment_key(PaymentAttemptStatus status) {
		// given
		saveAttempt(payment.getId(), status);

		// when / then
		assertRequestMismatch(command("changed-key"));
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getPaymentKey()).isNull();
	}

	@Test
	@DisplayName("취소 attemptId를 승인 요청에 사용하면 요청 불일치로 거절한다")
	void rejects_cancellation_attempt() {
		// given
		PaymentAttempt attempt = saveAttempt(payment.getId(), PaymentAttemptStatus.SUCCEEDED);
		jdbcTemplate.update("update payment_attempt set attempt_type = 'CANCELLATION' where payment_attempt_id = ?",
			attempt.getId());

		// when / then
		assertRequestMismatch(command("original-key"));
	}

	private void assertRequestMismatch(PaymentConfirmCommand command) {
		assertThatThrownBy(() -> paymentConfirmer.confirm(command, member.getMemberDetail().getMemberNo()))
			.isInstanceOf(BusinessException.class)
			.hasMessage("결제 시도 정보가 요청과 일치하지 않습니다.");
		verify(tossPaymentClient, never()).confirmPayment(any());
	}

	private PaymentConfirmCommand command(String paymentKey) {
		return new PaymentConfirmCommand(paymentKey, order.getOrderCode(), order.getTotalAmount(), "retry-id");
	}

	private PaymentAttempt saveAttempt(Long paymentId, PaymentAttemptStatus status) {
		PaymentAttempt attempt = PaymentAttempt.startApproval(paymentId, "retry-id", "original-key");
		switch (status) {
			case SUCCEEDED -> attempt.markSucceeded();
			case FAILED -> attempt.markFailed("REJECT", "카드 거절");
			case IN_PROGRESS -> { }
		}
		return attemptRepository.save(attempt);
	}
}
