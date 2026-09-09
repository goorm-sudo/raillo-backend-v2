package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentStatus;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.payment.adapter.persistence.PaymentJpaRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.OrderFixture;

@ServiceTest
class PaymentModifierTest {

	@Autowired
	private PaymentModifier paymentModifier;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private PaymentJpaRepository paymentRepository;

	private Member member;
	private Order order;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		order = orderRepository.save(
			OrderFixture.builder()
				.withMember(member)
				.withTotalAmount(BigDecimal.valueOf(50000))
				.build()
		);
	}

	@Test
	@DisplayName("Payment 생성 시 PENDING 상태로 저장된다")
	void createPayment_success() {
		// when
		Payment payment = paymentModifier.createPayment(member, order);

		// then
		assertThat(payment.getId()).isNotNull();
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(payment.getMember().getId()).isEqualTo(member.getId());
		assertThat(payment.getOrder().getId()).isEqualTo(order.getId());
		assertThat(payment.getAmount()).isEqualByComparingTo(order.getTotalAmount());
		assertThat(payment.getOrderCode()).isEqualTo(order.getOrderCode());
	}

	@Test
	@DisplayName("Order로 Payment를 조회할 수 있다")
	void getPaymentByOrder_success() {
		// given
		Payment savedPayment = paymentModifier.createPayment(member, order);

		// when
		Payment foundPayment = paymentModifier.getPaymentByOrder(order);

		// then
		assertThat(foundPayment.getId()).isEqualTo(savedPayment.getId());
	}

	@Test
	@DisplayName("존재하지 않는 Order로 Payment 조회 시 예외가 발생한다")
	void getPaymentByOrder_notFound_throwsException() {
		// given
		Order otherOrder = orderRepository.save(
			OrderFixture.builder()
				.withMember(member)
				.withTotalAmount(BigDecimal.valueOf(10000))
				.build()
		);

		// when & then
		assertThatThrownBy(() -> paymentModifier.getPaymentByOrder(otherOrder))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_NOT_FOUND)
			.hasMessage(PaymentError.PAYMENT_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("PaymentKey가 정상적으로 업데이트된다")
	void updatePaymentKeyInNewTransaction_success() {
		// given
		Payment payment = paymentModifier.createPayment(member, order);
		String paymentKey = "toss_payment_key_12345";

		// when
		paymentModifier.updatePaymentKeyInNewTransaction(payment.getId(), paymentKey);

		// then
		Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
		assertThat(updatedPayment.getPaymentKey()).isEqualTo(paymentKey);
	}

	@Test
	@DisplayName("Payment 실패 처리가 정상적으로 수행된다")
	void failPaymentInNewTransaction_success() {
		// given
		Payment payment = paymentModifier.createPayment(member, order);
		String failureCode = "REJECT_CARD_PAYMENT";
		String failureMessage = "카드 결제가 거절되었습니다.";

		// when
		paymentModifier.failPaymentInNewTransaction(payment.getId(), failureCode, failureMessage);

		// then
		Payment failedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
		assertThat(failedPayment.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(failedPayment.getFailureCode()).isEqualTo(failureCode);
		assertThat(failedPayment.getFailureMessage()).isEqualTo(failureMessage);
		assertThat(failedPayment.getFailedAt()).isNotNull();
	}

	@Test
	@DisplayName("존재하지 않는 Payment 실패 처리 시 예외가 발생한다")
	void failPaymentInNewTransaction_notFound_throwsException() {
		// given
		Long nonExistentPaymentId = 9999L;

		// when & then
		assertThatThrownBy(() -> paymentModifier.failPaymentInNewTransaction(
			nonExistentPaymentId, "ERROR_CODE", "에러 메시지"))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_NOT_FOUND)
			.hasMessage(PaymentError.PAYMENT_NOT_FOUND.getMessage());
	}
}
