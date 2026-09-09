package com.sudo.raillo.payment.domain.exception;

import com.sudo.raillo.common.exception.ExternalApiException;

public class TossPaymentException extends ExternalApiException {

	public TossPaymentException(int httpStatus, String errorCode, String errorMessage) {
		super(httpStatus, errorCode, errorMessage, "TOSS", "PAYMENT");
	}
}
