package com.sudo.raillo.payment.adapter.integration.toss;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 토스페이먼츠 개별 취소 내역 DTO
 *
 * Payment.cancels 배열의 각 항목, 부분 취소를 여러 번 하면 여러 개가 쌓임
 */
public record TossCancelDetail(

	@Schema(description = "취소 거래 고유 키", example = "090A796806E726BBB929F4A2CA7DB9A7")
	String transactionKey,

	@Schema(description = "취소 사유", example = "고객 변심")
	String cancelReason,

	@Schema(description = "취소 일시", example = "2024-02-13T12:20:23+09:00")
	OffsetDateTime canceledAt,

	@Schema(description = "취소 금액", example = "10000")
	int cancelAmount,

	@Schema(description = "취소 후 환불 가능 잔액", example = "40000")
	int refundableAmount,

	@Schema(description = "취소 상태 (DONE, FAILED 등)", example = "DONE")
	String cancelStatus
) {
}
