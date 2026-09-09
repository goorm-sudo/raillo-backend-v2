package com.sudo.raillo.payment.application;

import java.math.BigDecimal;

import com.sudo.raillo.order.domain.Order;

/**
 * 결제 준비 유스케이스의 결과 record.
 *
 * <p>Order 도메인 엔티티를 provided port 계약에 노출하지 않기 위한 스냅샷.
 */
public record PaymentPrepareResult(
	String orderCode,
	BigDecimal totalAmount
) {
	public static PaymentPrepareResult from(Order order) {
		return new PaymentPrepareResult(order.getOrderCode(), order.getTotalAmount());
	}
}
