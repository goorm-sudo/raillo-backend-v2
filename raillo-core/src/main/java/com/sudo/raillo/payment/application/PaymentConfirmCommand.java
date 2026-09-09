package com.sudo.raillo.payment.application;

import java.math.BigDecimal;

public record PaymentConfirmCommand(
	String paymentKey,
	String orderId,
	BigDecimal amount
) {
}
