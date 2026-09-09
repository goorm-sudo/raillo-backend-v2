package com.sudo.raillo.payment.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sudo.raillo.common.exception.DomainException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.domain.PaymentStatus;
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.OrderFixture;

class PaymentTest {

	private Member member;
	private Order order;

	@BeforeEach
	void setUp() {
		member = MemberFixture.create();
		order = OrderFixture.builder()
			.withMember(member)
			.withTotalAmount(BigDecimal.valueOf(10000))
			.build();
	}

	@Test
	@DisplayName("Payment 생성 시 PENDING 상태이며 Order 정보가 설정된다")
	void create_success() {
		// when
		Payment payment = Payment.create(member, order);

		// then
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(payment.getMember()).isEqualTo(member);
		assertThat(payment.getOrder()).isEqualTo(order);
		assertThat(payment.getOrderCode()).isEqualTo(order.getOrderCode());
		assertThat(payment.getAmount()).isEqualTo(order.getTotalAmount());
		assertThat(payment.getPaymentMethod()).isNull();
		assertThat(payment.getPaidAt()).isNull();
	}

	@Test
	@DisplayName("paymentKey가 정상적으로 업데이트된다")
	void updatePaymentKey_success() {
		// given
		Payment payment = Payment.create(member, order);
		String paymentKey = "toss_payment_key_12345";

		// when
		payment.updatePaymentKey(paymentKey);

		// then
		assertThat(payment.getPaymentKey()).isEqualTo(paymentKey);
	}

	@Test
	@DisplayName("결제 승인 시 PAID 상태로 변경되고 paymentMethod, paidAt이 설정된다")
	void approve_success() {
		// given
		Payment payment = Payment.create(member, order);
		PaymentMethod paymentMethod = PaymentMethod.CREDIT_CARD;

		// when
		payment.approve(paymentMethod);

		// then
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(payment.getPaymentMethod()).isEqualTo(paymentMethod);
		assertThat(payment.getPaidAt()).isNotNull();
	}

	@Test
	@DisplayName("PENDING 상태가 아닐 때 결제 승인 시 예외가 발생한다")
	void approve_whenNotPending_throwsException() {
		// given
		Payment payment = Payment.create(member, order);
		payment.approve(PaymentMethod.CREDIT_CARD);

		// when & then
		assertThatThrownBy(() -> payment.approve(PaymentMethod.CREDIT_CARD))
			.isInstanceOf(DomainException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_NOT_APPROVABLE)
			.hasMessage(PaymentError.PAYMENT_NOT_APPROVABLE.getMessage());
	}

	@Test
	@DisplayName("결제 취소 시 CANCELLED 상태로 변경되고 cancelledAt이 설정된다")
	void cancel_success() {
		// given
		Payment payment = Payment.create(member, order);
		String reason = "고객 요청";

		// when
		payment.cancel(reason);

		// then
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
		assertThat(payment.getCancelledAt()).isNotNull();
	}

	@Test
	@DisplayName("PENDING 상태가 아닐 때 결제 취소 시 예외가 발생한다")
	void cancel_whenNotPending_throwsException() {
		// given
		Payment payment = Payment.create(member, order);
		payment.approve(PaymentMethod.CREDIT_CARD);

		// when & then
		assertThatThrownBy(() -> payment.cancel("취소 사유"))
			.isInstanceOf(DomainException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_NOT_CANCELLABLE)
			.hasMessage(PaymentError.PAYMENT_NOT_CANCELLABLE.getMessage());
	}

	@Test
	@DisplayName("환불 처리 시 REFUNDED 상태로 변경되고 refundedAt이 설정된다")
	void refund_success() {
		// given
		Payment payment = Payment.create(member, order);
		payment.approve(PaymentMethod.CREDIT_CARD);

		// when
		payment.refund();

		// then
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
		assertThat(payment.getRefundedAt()).isNotNull();
	}

	@Test
	@DisplayName("PAID 상태가 아닐 때 환불 시 예외가 발생한다")
	void refund_whenNotPaid_throwsException() {
		// given
		Payment payment = Payment.create(member, order);

		// when & then
		assertThatThrownBy(() -> payment.refund())
			.isInstanceOf(DomainException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_NOT_REFUNDABLE)
			.hasMessage(PaymentError.PAYMENT_NOT_REFUNDABLE.getMessage());
	}

	@Test
	@DisplayName("이미 환불된 결제를 다시 환불하면 예외가 발생한다")
	void refund_alreadyRefunded_throwsException() {
		// given
		Payment payment = Payment.create(member, order);
		payment.approve(PaymentMethod.CREDIT_CARD);
		payment.refund();

		// when & then
		assertThatThrownBy(() -> payment.refund())
			.isInstanceOf(DomainException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_NOT_REFUNDABLE)
			.hasMessage(PaymentError.PAYMENT_NOT_REFUNDABLE.getMessage());
	}

	@Test
	@DisplayName("결제 실패 시 FAILED 상태로 변경되고 실패 정보가 설정된다")
	void fail_success() {
		// given
		Payment payment = Payment.create(member, order);
		String failureCode = "REJECT_CARD_PAYMENT";
		String failureMessage = "카드 결제가 거절되었습니다.";

		// when
		payment.fail(failureCode, failureMessage);

		// then
		assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getFailureCode()).isEqualTo(failureCode);
		assertThat(payment.getFailureMessage()).isEqualTo(failureMessage);
		assertThat(payment.getFailedAt()).isNotNull();
	}

	@Test
	@DisplayName("PENDING 상태가 아닐 때 결제 실패 처리 시 예외가 발생한다")
	void fail_whenNotPending_throwsException() {
		// given
		Payment payment = Payment.create(member, order);
		payment.approve(PaymentMethod.CREDIT_CARD);

		// when & then
		assertThatThrownBy(() -> payment.fail("ERROR_CODE", "에러 메시지"))
			.isInstanceOf(DomainException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_CANNOT_FAIL)
			.hasMessage(PaymentError.PAYMENT_CANNOT_FAIL.getMessage());
	}
}
