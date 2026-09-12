package com.sudo.raillo.payment.domain.exception;

import com.sudo.raillo.common.exception.ExternalApiException;

public class TossPaymentException extends ExternalApiException {

	public TossPaymentException(int httpStatus, String errorCode, String errorMessage) {
		super(httpStatus, errorCode, errorMessage, "TOSS", "PAYMENT");
	}

	/**
	 * 4xx 응답은 요청 거절이 확정된 것으로 본다. 5xx는 Toss가 승인한 뒤 응답에 실패했을 수 있으므로
	 * 결과 불명으로 분류해 PaymentAttempt를 IN_PROGRESS로 유지한다.
	 */
	public boolean isDefinitiveFailure() {
		return getHttpStatus() >= 400 && getHttpStatus() < 500;
	}
}
