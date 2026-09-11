package com.sudo.raillo.payment.application;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentConfirmCommand(
	String paymentKey,
	String orderId,
	BigDecimal amount,
	String attemptId
) {
	public PaymentConfirmCommand(String paymentKey, String orderId, BigDecimal amount) {
		this(paymentKey, orderId, amount, null);
	}

	public PaymentConfirmCommand withGeneratedAttemptIdIfMissing() {
		if (attemptId == null || attemptId.isBlank()) {
			return new PaymentConfirmCommand(paymentKey, orderId, amount, UUID.randomUUID().toString());
		}
		return this;
	}
}
