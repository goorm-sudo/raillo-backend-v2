package com.sudo.raillo.payment.application;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayConfirmResult;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentStatus;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentValidator {

	private final PaymentRepository paymentRepository;

	public void validatePaymentOwner(Payment payment, Member member) {
		if (!payment.getMember().getId().equals(member.getId())) {
			log.error("[소유자 불일치] Payment의 소유자가 아님: paymentId={}, requestMemberId={}, paymentMemberId={}",
				payment.getId(), member.getId(), payment.getMember().getId());
			throw new BusinessException(PaymentError.PAYMENT_ACCESS_DENIED);
		}
	}

	public void validateDuplicatePayment(Order order) {
		boolean exists = paymentRepository.existsByOrderAndPaymentStatus(order, PaymentStatus.PAID);
		if (exists) {
			throw new BusinessException(PaymentError.PAYMENT_ALREADY_COMPLETED);
		}
	}

	/**
	 * 클라이언트 요청 금액, Order 금액, Payment 금액 모두 일치 여부 검증.
	 */
	public void validateAmounts(BigDecimal requestAmount, BigDecimal orderAmount, BigDecimal paymentAmount) {
		if (requestAmount.compareTo(orderAmount) != 0) {
			log.error("[금액 불일치] 요청 금액 != Order 금액: requestAmount={}, orderAmount={}", requestAmount, orderAmount);
			throw new BusinessException(PaymentError.PAYMENT_AMOUNT_MISMATCH);
		}

		if (orderAmount.compareTo(paymentAmount) != 0) {
			log.error("[금액 불일치] Order 금액 != Payment 금액: orderAmount={}, paymentAmount={}", orderAmount, paymentAmount);
			throw new BusinessException(PaymentError.PAYMENT_AMOUNT_MISMATCH);
		}

		log.debug("[금액 검증 통과] requestAmount={}, orderAmount={}, paymentAmount={}", requestAmount, orderAmount, paymentAmount);
	}

	/**
	 * 게이트웨이 응답이 원 요청과 일치하는지 검증한다.
	 */
	public void validateGatewayResponseMatchesRequest(GatewayConfirmResult result, PaymentConfirmCommand command) {
		if (result.totalAmount().compareTo(command.amount()) != 0) {
			log.error("[게이트웨이 응답 금액 불일치] gatewayAmount={}, requestAmount={}", result.totalAmount(), command.amount());
			throw new BusinessException(
				PaymentError.PAYMENT_AMOUNT_MISMATCH,
				String.format("게이트웨이 결제 금액이 요청 금액과 일치하지 않습니다. (게이트웨이: %s, 요청: %s)", result.totalAmount(), command.amount())
			);
		}

		if (!result.paymentKey().equals(command.paymentKey())) {
			log.error("[게이트웨이 응답 paymentKey 불일치] gatewayPaymentKey={}, requestPaymentKey={}", result.paymentKey(), command.paymentKey());
			throw new BusinessException(
				PaymentError.PAYMENT_KEY_MISMATCH,
				String.format("게이트웨이 결제 키가 요청 키와 일치하지 않습니다. (게이트웨이: %s, 요청: %s)", result.paymentKey(), command.paymentKey())
			);
		}

		log.debug("[게이트웨이 응답 검증 통과] paymentKey={}, amount={}", result.paymentKey(), result.totalAmount());
	}
}
