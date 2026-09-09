package com.sudo.raillo.payment.application;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.payment.domain.PaymentStatus;

/**
 * 결제 승인 유스케이스의 결과 record.
 *
 * <p>도메인 엔티티를 provided port 계약에 노출하지 않기 위한 스냅샷.
 */
public record PaymentConfirmResult(
	Long paymentId,
	String orderCode,
	String paymentKey,
	BigDecimal amount,
	PaymentMethod paymentMethod,
	PaymentStatus paymentStatus,
	LocalDateTime paidAt
) {
	public static PaymentConfirmResult from(Payment payment) {
		return new PaymentConfirmResult(
			payment.getId(),
			payment.getOrderCode(),
			payment.getPaymentKey(),
			payment.getAmount(),
			payment.getPaymentMethod(),
			payment.getPaymentStatus(),
			payment.getPaidAt()
		);
	}
}
