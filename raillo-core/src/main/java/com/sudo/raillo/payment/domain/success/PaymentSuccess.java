package com.sudo.raillo.payment.domain.success;

import org.springframework.http.HttpStatus;

import com.sudo.raillo.common.response.SuccessCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum PaymentSuccess implements SuccessCode {

	// 결제 처리 관련
	PAYMENT_PREPARE_SUCCESS(HttpStatus.OK, "결제 준비가 완료되었습니다."),
	PAYMENT_CONFIRM_SUCCESS(HttpStatus.OK, "결제 승인이 완료되었습니다."),
	PAYMENT_PROCESS_SUCCESS(HttpStatus.OK, "결제가 성공적으로 처리되었습니다."),
	PAYMENT_CANCEL_SUCCESS(HttpStatus.OK, "결제가 성공적으로 취소되었습니다."),

	// 결제 조회 관련
	PAYMENT_HISTORY_SUCCESS(HttpStatus.OK, "결제 내역을 성공적으로 조회했습니다."),
	PAYMENT_DETAIL_SUCCESS(HttpStatus.OK, "결제 상세 정보를 성공적으로 조회했습니다.");

	private final HttpStatus status;
	private final String message;
}
