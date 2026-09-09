package com.sudo.raillo.payment.adapter.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.payment.domain.exception.TossPaymentException;

import lombok.RequiredArgsConstructor;

@Aspect
@Component
@RequiredArgsConstructor
public class PaymentMetricsAspect {

	private final PaymentMetrics paymentMetrics;

	@Around("execution(* com.sudo.raillo.payment.application.PaymentPrepareService.prepare(..))")
	public Object measurePrepare(ProceedingJoinPoint joinPoint) throws Throwable {
		Object result = joinPoint.proceed();
		paymentMetrics.incrementPrepare();
		return result;
	}

	@Around("execution(* com.sudo.raillo.payment.application.PaymentConfirmService.confirm(..))")
	public Object measureConfirm(ProceedingJoinPoint joinPoint) throws Throwable {
		try {
			Object result = joinPoint.proceed();
			paymentMetrics.incrementConfirmSuccess();
			return result;
		} catch (TossPaymentException e) {
			paymentMetrics.incrementConfirmFailure("toss_error", e.getHttpStatus(), e.getErrorCode());
			throw e;
		} catch (BusinessException e) {
			int httpStatus = e.getErrorCode().getStatus().value();
			String reason = e.getErrorCode().getStatus().is5xxServerError()
				? "system_error" : "validation_error";
			paymentMetrics.incrementConfirmFailure(reason, httpStatus, e.getErrorCode().getCode());
			throw e;
		} catch (Exception e) {
			paymentMetrics.incrementConfirmFailure("system_error", 500, "UNKNOWN");
			throw e;
		}
	}
}
