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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.OrderTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;

@ServiceTest
class PaymentConfirmRetryTest {

	@Autowired private PaymentConfirmer paymentConfirmer;
	@MockitoSpyBean private PaymentRepository paymentRepository;
	@Autowired private PlatformTransactionManager transactionManager;
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

	@Test
	@DisplayName("Payment 조회 후 다른 트랜잭션이 승인을 확정하면 재요청은 최신 결제 결과를 반환한다")
	void returns_committed_result_when_payment_was_loaded_before_approval() {
		// given: 재요청이 Payment를 읽은 직후 첫 요청의 확정 커밋이 발생하도록 순서를 고정한다.
		PaymentAttempt attempt = saveAttempt(payment.getId(), PaymentAttemptStatus.IN_PROGRESS);
		TransactionTemplate independent = new TransactionTemplate(transactionManager);
		independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		doAnswer(invocation -> {
			Payment loaded = (Payment) ((java.util.Optional<?>) invocation.callRealMethod()).orElseThrow();
			independent.executeWithoutResult(status -> {
				Payment committed = paymentRepository.findById(payment.getId()).orElseThrow();
				committed.updatePaymentKey("original-key");
				committed.approve(PaymentMethod.CREDIT_CARD);
				attemptRepository.findById(attempt.getId()).orElseThrow().markSucceeded();
			});
			assertThat(loaded.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
			return java.util.Optional.of(loaded);
		}).when(paymentRepository).findByOrder(any(Order.class));

		// when
		PaymentConfirmResult result = paymentConfirmer.confirm(command("original-key"), member.getMemberDetail().getMemberNo());

		// then
		assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(result.paymentKey()).isEqualTo("original-key");
		assertThat(result.paymentMethod()).isEqualTo(PaymentMethod.CREDIT_CARD);
		assertThat(result.paidAt()).isNotNull();
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
