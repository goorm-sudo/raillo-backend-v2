package com.sudo.raillo.payment.adapter.integration.toss;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "토스 결제 승인 응답")
public record TossPaymentConfirmResponse(

	@Schema(description = "결제 고유 키 (토스에서 발급)", example = "PAY_1Q2w3E4r5T6y7U8i9O0p")
	String paymentKey,

	@Schema(description = "주문번호 (상점에서 생성)", example = "ORDER_20250201_ABCD1234")
	String orderId,

	@Schema(description = "결제 수단", example = "CARD")
	String method,

	@Schema(description = "총 결제 금액", example = "55000")
	Long totalAmount,

	@Schema(description = "결제 처리 상태", example = "DONE")
	String status
) {
}
