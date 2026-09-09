package com.sudo.raillo.payment.adapter.webapi.dto;

import java.math.BigDecimal;

import com.sudo.raillo.payment.application.PaymentConfirmCommand;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 결제 승인 요청 DTO
 */
public record PaymentConfirmRequest(
	@NotBlank(message = "paymentKey는 필수입니다")
	String paymentKey,

	@NotBlank(message = "orderId는 필수입니다")
	String orderId,

	@NotNull(message = "amount는 필수입니다")
	@Positive(message = "amount는 0보다 커야 합니다")
	BigDecimal amount
) {
	public PaymentConfirmCommand toCommand() {
		return new PaymentConfirmCommand(paymentKey, orderId, amount);
	}
}
