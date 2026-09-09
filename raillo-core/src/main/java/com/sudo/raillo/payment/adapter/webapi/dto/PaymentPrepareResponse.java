package com.sudo.raillo.payment.adapter.webapi.dto;

import java.math.BigDecimal;

import com.sudo.raillo.payment.application.PaymentPrepareResult;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "결제 준비 응답 DTO")
public record PaymentPrepareResponse(

	@Schema(description = "주문 ID, Order의 orderCode")
	String orderId,

	@Schema(description = "결제 금액", example = "50000")
	BigDecimal amount
) {
	public static PaymentPrepareResponse from(PaymentPrepareResult result) {
		return new PaymentPrepareResponse(result.orderCode(), result.totalAmount());
	}
}
