package com.sudo.raillo.payment.domain.exception;

import org.springframework.http.HttpStatus;

import com.sudo.raillo.common.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum PaymentError implements ErrorCode {

	// 결제 상태 (1xx)
	PAYMENT_NOT_FOUND("결제 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "PAYMENT_101"),
	PAYMENT_ACCESS_DENIED("결제에 대한 접근 권한이 없습니다.", HttpStatus.FORBIDDEN, "PAYMENT_102"),
	PAYMENT_ALREADY_COMPLETED("이미 결제가 완료된 주문입니다.", HttpStatus.BAD_REQUEST, "PAYMENT_103"),
	PAYMENT_NOT_CANCELLABLE("취소할 수 없는 결제 상태입니다.", HttpStatus.BAD_REQUEST, "PAYMENT_104"),
	PAYMENT_NOT_APPROVABLE("승인할 수 없는 결제 상태입니다.", HttpStatus.BAD_REQUEST, "PAYMENT_105"),
	PAYMENT_NOT_REFUNDABLE("환불할 수 없는 결제 상태입니다.", HttpStatus.BAD_REQUEST, "PAYMENT_106"),
	PAYMENT_CANNOT_FAIL("실패 처리할 수 없는 결제 상태입니다.", HttpStatus.BAD_REQUEST, "PAYMENT_107"),

	// 금액/수단/키 (2xx)
	PAYMENT_AMOUNT_MISMATCH("결제 금액이 일치하지 않습니다.", HttpStatus.BAD_REQUEST, "PAYMENT_201"),
	INVALID_PAYMENT_METHOD("지원하지 않는 결제 수단입니다.", HttpStatus.BAD_REQUEST, "PAYMENT_202"),
	PAYMENT_KEY_MISMATCH("결제 키가 일치하지 않습니다.", HttpStatus.BAD_REQUEST, "PAYMENT_203"),

	// 시스템 (9xx)
	PAYMENT_SYSTEM_ERROR("결제 시스템 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT_901");

	private final String message;
	private final HttpStatus status;
	private final String code;
}
