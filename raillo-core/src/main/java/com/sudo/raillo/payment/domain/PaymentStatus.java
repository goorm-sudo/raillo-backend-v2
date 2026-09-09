package com.sudo.raillo.payment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentStatus {

	PENDING("결제대기", "결제 요청이 생성된 상태"),
	PAID("결제완료", "결제가 성공적으로 완료된 상태"),
	CANCELLED("결제취소", "결제가 취소된 상태"),
	REFUNDED("환불완료", "결제가 환불된 상태"),
	FAILED("결제실패", "결제 처리가 실패한 상태");

	private final String displayName;
	private final String description;
}
