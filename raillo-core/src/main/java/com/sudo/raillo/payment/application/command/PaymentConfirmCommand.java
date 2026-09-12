package com.sudo.raillo.payment.application.command;

import com.sudo.raillo.payment.application.PaymentAttemptIds;
import java.math.BigDecimal;

public record PaymentConfirmCommand(
	String paymentKey,
	String orderId,
	BigDecimal amount,
	String attemptId
) {
	public PaymentConfirmCommand(String paymentKey, String orderId, BigDecimal amount) {
		this(paymentKey, orderId, amount, null);
	}

	/**
	 * 클라이언트가 attemptId를 명시했으면 그 값을, 없으면 paymentKey에서 파생한 값을 반환한다.
	 * 파생 규칙은 {@link PaymentAttemptIds#forApproval(String)}에 있다.
	 */
	public String attemptIdOrDerived() {
		if (attemptId != null && !attemptId.isBlank()) {
			return attemptId;
		}
		return PaymentAttemptIds.forApproval(paymentKey);
	}
}
