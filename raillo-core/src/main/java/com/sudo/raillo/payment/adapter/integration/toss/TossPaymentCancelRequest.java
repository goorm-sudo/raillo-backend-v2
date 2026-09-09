package com.sudo.raillo.payment.adapter.integration.toss;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 토스페이먼츠 결제 취소 API 요청 DTO
 *
 * POST /v1/payments/{paymentKey}/cancel
 *
 * @param cancelReason 결제를 취소하는 이유 (필수, 최대 200자)
 * @param cancelAmount 취소할 금액 (부분 취소 시 필수, null이면 전액 취소)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TossPaymentCancelRequest(
	String cancelReason,
	Integer cancelAmount
) {
}
