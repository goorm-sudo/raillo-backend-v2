package com.sudo.raillo.payment.adapter.webapi.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.sudo.raillo.payment.application.PaymentConfirmResult;
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.payment.domain.PaymentStatus;

public record PaymentConfirmResponse(
	Long paymentId,
	String orderId,
	String paymentKey,
	BigDecimal amount,
	PaymentMethod paymentMethod,
	PaymentStatus paymentStatus,
	LocalDateTime paidAt
) {
	public static PaymentConfirmResponse from(PaymentConfirmResult result) {
		return new PaymentConfirmResponse(
			result.paymentId(),
			result.orderCode(),
			result.paymentKey(),
			result.amount(),
			result.paymentMethod(),
			result.paymentStatus(),
			result.paidAt()
		);
	}
}
