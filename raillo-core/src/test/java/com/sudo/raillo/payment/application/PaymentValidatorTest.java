package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.adapter.persistence.PaymentJpaRepository;
import com.sudo.raillo.payment.application.required.PaymentGateway;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.application.PaymentConfirmCommand;
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;

@ServiceTest
class PaymentValidatorTest {

	@Autowired
	private PaymentValidator paymentValidator;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private PaymentJpaRepository paymentRepository;

	private Member member;
	private Member otherMember;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		otherMember = memberRepository.save(MemberFixture.createOther());
	}

	@Nested
	@DisplayName("validateAmounts")
	class ValidateAmounts {

		@Test
		@DisplayName("요청 금액, Order 금액, Payment 금액이 모두 일치하면 검증을 통과한다")
		void success_allAmountsMatch() {
			BigDecimal amount = BigDecimal.valueOf(50000);
			assertThatCode(() -> paymentValidator.validateAmounts(amount, amount, amount))
				.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("요청 금액과 Order 금액이 다르면 PAYMENT_AMOUNT_MISMATCH 예외가 발생한다")
		void fail_requestAmountNotMatchOrderAmount() {
			BigDecimal requestAmount = BigDecimal.valueOf(50000);
			BigDecimal orderAmount = BigDecimal.valueOf(60000);
			BigDecimal paymentAmount = BigDecimal.valueOf(60000);

			assertThatThrownBy(() -> paymentValidator.validateAmounts(requestAmount, orderAmount, paymentAmount))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_AMOUNT_MISMATCH);
		}

		@Test
		@DisplayName("Order 금액과 Payment 금액이 다르면 PAYMENT_AMOUNT_MISMATCH 예외가 발생한다")
		void fail_orderAmountNotMatchPaymentAmount() {
			BigDecimal requestAmount = BigDecimal.valueOf(50000);
			BigDecimal orderAmount = BigDecimal.valueOf(50000);
			BigDecimal paymentAmount = BigDecimal.valueOf(60000);

			assertThatThrownBy(() -> paymentValidator.validateAmounts(requestAmount, orderAmount, paymentAmount))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_AMOUNT_MISMATCH);
		}
	}

	@Nested
	@DisplayName("validatePaymentOwner")
	class ValidatePaymentOwner {

		@Test
		@DisplayName("Payment의 소유자와 요청 회원이 일치하면 검증을 통과한다")
		void success_ownerMatches() {
			Order order = orderRepository.save(Order.create(member, BigDecimal.valueOf(50000)));
			Payment payment = paymentRepository.save(Payment.create(member, order));

			assertThatCode(() -> paymentValidator.validatePaymentOwner(payment, member))
				.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("Payment의 소유자와 요청 회원이 다르면 PAYMENT_ACCESS_DENIED 예외가 발생한다")
		void fail_ownerMismatch() {
			Order order = orderRepository.save(Order.create(member, BigDecimal.valueOf(50000)));
			Payment payment = paymentRepository.save(Payment.create(member, order));

			assertThatThrownBy(() -> paymentValidator.validatePaymentOwner(payment, otherMember))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_ACCESS_DENIED);
		}
	}

	@Nested
	@DisplayName("validateDuplicatePayment")
	class ValidateDuplicatePayment {

		@Test
		@DisplayName("해당 주문에 PAID 상태의 결제가 없으면 검증을 통과한다")
		void success_noDuplicatePayment() {
			Order order = orderRepository.save(Order.create(member, BigDecimal.valueOf(50000)));
			paymentRepository.save(Payment.create(member, order));

			assertThatCode(() -> paymentValidator.validateDuplicatePayment(order))
				.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("해당 주문에 이미 PAID 상태의 결제가 있으면 PAYMENT_ALREADY_COMPLETED 예외가 발생한다")
		void fail_alreadyPaidPaymentExists() {
			Order order = orderRepository.save(Order.create(member, BigDecimal.valueOf(50000)));
			Payment payment = paymentRepository.save(Payment.create(member, order));
			payment.approve(PaymentMethod.CREDIT_CARD);
			paymentRepository.saveAndFlush(payment);

			assertThatThrownBy(() -> paymentValidator.validateDuplicatePayment(order))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_ALREADY_COMPLETED);
		}
	}

	@Nested
	@DisplayName("validateGatewayResponseMatchesRequest")
	class ValidateGatewayResponseMatchesRequest {

		@Test
		@DisplayName("게이트웨이 응답의 금액과 paymentKey가 요청과 일치하면 검증을 통과한다")
		void success_responseMatchesRequest() {
			String paymentKey = "gw_pk_test";
			BigDecimal amount = BigDecimal.valueOf(50000);

			PaymentGateway.GatewayConfirmResult result = new PaymentGateway.GatewayConfirmResult(
				paymentKey, "ORDER_001", amount, PaymentMethod.CREDIT_CARD);
			PaymentConfirmCommand request = new PaymentConfirmCommand(paymentKey, "ORDER_001", amount);

			assertThatCode(() -> paymentValidator.validateGatewayResponseMatchesRequest(result, request))
				.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("게이트웨이 응답 금액이 요청 금액과 다르면 PAYMENT_AMOUNT_MISMATCH 예외가 발생한다")
		void fail_amountMismatch() {
			String paymentKey = "gw_pk_test";

			PaymentGateway.GatewayConfirmResult result = new PaymentGateway.GatewayConfirmResult(
				paymentKey, "ORDER_001", BigDecimal.valueOf(60000), PaymentMethod.CREDIT_CARD);
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				paymentKey, "ORDER_001", BigDecimal.valueOf(50000));

			assertThatThrownBy(() -> paymentValidator.validateGatewayResponseMatchesRequest(result, request))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_AMOUNT_MISMATCH);
		}

		@Test
		@DisplayName("게이트웨이 응답 paymentKey가 요청 paymentKey와 다르면 PAYMENT_KEY_MISMATCH 예외가 발생한다")
		void fail_paymentKeyMismatch() {
			BigDecimal amount = BigDecimal.valueOf(50000);

			PaymentGateway.GatewayConfirmResult result = new PaymentGateway.GatewayConfirmResult(
				"gw_pk_different", "ORDER_001", amount, PaymentMethod.CREDIT_CARD);
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				"gw_pk_original", "ORDER_001", amount);

			assertThatThrownBy(() -> paymentValidator.validateGatewayResponseMatchesRequest(result, request))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_KEY_MISMATCH);
		}
	}
}
