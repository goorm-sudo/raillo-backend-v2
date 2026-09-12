package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.payment.application.result.PaymentConfirmResult;
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
import com.sudo.raillo.payment.adapter.persistence.PaymentJpaRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.payment.domain.PaymentStatus;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.OrderFixture;

@ServiceTest
class PaymentReaderTest {

	@Autowired
	private PaymentReader paymentReader;

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
	@DisplayName("Order로 Payment를 조회할 수 있다")
	void getPaymentByOrder_success() {
		// given
		Payment savedPayment = paymentRepository.save(Payment.create(member, order));

		// when
		Payment foundPayment = paymentReader.getPaymentByOrder(order);

		// then
		assertThat(foundPayment.getId()).isEqualTo(savedPayment.getId());
	}

	@Test
	@DisplayName("Payment가 없는 Order로 조회하면 PAYMENT_NOT_FOUND 예외를 던진다")
	void getPaymentByOrder_notFound_throwsException() {
		// given
		Order otherOrder = orderRepository.save(
			OrderFixture.builder()
				.withMember(member)
				.withTotalAmount(BigDecimal.valueOf(10000))
				.build()
		);

		// when / then
		assertThatThrownBy(() -> paymentReader.getPaymentByOrder(otherOrder))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_NOT_FOUND);
	}

	@Test
	@DisplayName("PAID 상태의 Payment에 대해 getConfirmResult가 모든 필드를 정확히 매핑한다")
	void getConfirmResult_populatesAllFieldsFromDb() {
		// given: 승인 완료된 Payment 저장
		Payment payment = Payment.create(member, order);
		payment.updatePaymentKey("toss_pk_projection_test");
		payment.approve(PaymentMethod.CREDIT_CARD);
		Payment saved = paymentRepository.save(payment);

		// when
		PaymentConfirmResult result = paymentReader.getConfirmResult(saved.getId());

		// then: JPQL constructor expression이 모든 컬럼을 올바른 위치에 매핑했는지 검증
		assertThat(result.paymentId()).isEqualTo(saved.getId());
		assertThat(result.orderCode()).isEqualTo(order.getOrderCode());
		assertThat(result.paymentKey()).isEqualTo("toss_pk_projection_test");
		assertThat(result.amount()).isEqualByComparingTo(order.getTotalAmount());
		assertThat(result.paymentMethod()).isEqualTo(PaymentMethod.CREDIT_CARD);
		assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(result.paidAt()).isNotNull();
	}

	@Test
	@DisplayName("존재하지 않는 paymentId로 getConfirmResult 호출 시 PAYMENT_NOT_FOUND 예외를 던진다")
	void getConfirmResult_notFound_throwsException() {
		// when / then
		assertThatThrownBy(() -> paymentReader.getConfirmResult(9_999L))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_NOT_FOUND);
	}
}
