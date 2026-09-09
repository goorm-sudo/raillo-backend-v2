package com.sudo.raillo.payment.adapter.persistence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.querydsl.core.annotations.QueryProjection;
import com.sudo.raillo.payment.domain.PaymentStatus;
import com.sudo.raillo.payment.domain.PaymentMethod;

import lombok.Getter;

@Getter
public class PaymentProjection {

	private final Long paymentId;
	private final String paymentKey;
	private final String bookingCode;
	private final BigDecimal amount;
	private final PaymentMethod paymentMethod;
	private final PaymentStatus paymentStatus;
	private final LocalDateTime paidAt;
	private final LocalDateTime cancelledAt;
	private final LocalDateTime refundedAt;

	@QueryProjection
	public PaymentProjection(Long paymentId, String paymentKey, String bookingCode,
		BigDecimal amount, PaymentMethod paymentMethod, PaymentStatus paymentStatus,
		LocalDateTime paidAt, LocalDateTime cancelledAt, LocalDateTime refundedAt) {
		this.paymentId = paymentId;
		this.paymentKey = paymentKey;
		this.bookingCode = bookingCode;
		this.amount = amount;
		this.paymentMethod = paymentMethod;
		this.paymentStatus = paymentStatus;
		this.paidAt = paidAt;
		this.cancelledAt = cancelledAt;
		this.refundedAt = refundedAt;
	}
}
