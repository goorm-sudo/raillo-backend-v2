package com.sudo.raillo.payment.adapter.integration.toss;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 토스페이먼츠 결제 취소 API 응답 DTO
 *
 * POST /v1/payments/{paymentKey}/cancel 응답
 */
@Schema(description = "토스 결제 취소 응답")
public record TossPaymentCancelResponse(

	@Schema(description = "결제 고유 키", example = "5EnNZRJGvaBX7zk2yd8ydw26XvwXkLrx9POLqKQjmAw4b0e1")
	String paymentKey,

	@Schema(description = "주문번호", example = "a4CWyWY5m89PNh7xJwhk1")
	String orderId,

	@Schema(description = "결제 상태 (CANCELED: 전액 취소, PARTIAL_CANCELED: 부분 취소)", example = "CANCELED")
	String status,

	@Schema(description = "총 결제 금액, 최초에 결제된 결제 금액", example = "50000")
	int totalAmount,

	@Schema(description = "취소할 수 있는 금액(잔고), 결제 취소나 부분 취소가 되고 나서 남은 값", example = "0")
	int balanceAmount,

	@Schema(description = "취소 내역 목록 (부분 취소 시 여러 개)")
	List<TossCancelDetail> cancels,

	@Schema(description = "부분 취소 가능 여부", example = "true")
	boolean isPartialCancelable
) {
	public boolean isFullyCanceled() {
		return "CANCELED".equals(status);
	}

	public boolean isPartiallyCanceled() {
		return "PARTIAL_CANCELED".equals(status);
	}

	public int getTotalCanceledAmount() {
		if (cancels == null || cancels.isEmpty()) {
			return 0;
		}
		return cancels.stream()
			.mapToInt(TossCancelDetail::cancelAmount)
			.sum();
	}

	public TossCancelDetail getLatestCancel() {
		if (cancels == null || cancels.isEmpty()) {
			return null;
		}
		return cancels.get(cancels.size() - 1);
	}

	public int getCancelCount() {
		return cancels == null ? 0 : cancels.size();
	}
}
