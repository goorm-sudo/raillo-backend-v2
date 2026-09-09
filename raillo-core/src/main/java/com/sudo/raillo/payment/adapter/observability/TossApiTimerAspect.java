package com.sudo.raillo.payment.adapter.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import io.micrometer.core.instrument.Timer.Sample;
import lombok.RequiredArgsConstructor;

@Aspect
@Component
@RequiredArgsConstructor
public class TossApiTimerAspect {

	private final MeterRegistry meterRegistry;

	@Around("execution(* com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient.confirmPayment(..))")
	public Object timeConfirmPayment(ProceedingJoinPoint joinPoint) throws Throwable {
		return timeApiCall(joinPoint, "confirm");
	}

	@Around("execution(* com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient.cancelPayment(..))")
	public Object timeCancelPayment(ProceedingJoinPoint joinPoint) throws Throwable {
		return timeApiCall(joinPoint, "cancel");
	}

	private Object timeApiCall(ProceedingJoinPoint joinPoint, String operation) throws Throwable {
		Sample sample = Timer.start(meterRegistry);
		try {
			return joinPoint.proceed();
		} finally {
			sample.stop(Timer.builder("toss_api_duration_seconds")
				.description("Toss API 호출 응답 시간")
				.tag("operation", operation)
				.publishPercentileHistogram(true)
				.register(meterRegistry));
		}
	}
}
