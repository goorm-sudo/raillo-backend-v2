package com.sudo.raillo.payment.adapter.observability;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class PaymentMetrics {

	private final MeterRegistry meterRegistry;
	private final Counter prepareCounter;
	private final Counter confirmSuccessCounter;

	public PaymentMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;

		this.prepareCounter = Counter.builder("payment_prepare_total")
			.description("결제 준비 성공 건수")
			.register(meterRegistry);

		this.confirmSuccessCounter = Counter.builder("payment_confirm_success_total")
			.description("결제 승인 성공 건수")
			.register(meterRegistry);
	}

	public void incrementPrepare() {
		prepareCounter.increment();
	}

	public void incrementConfirmSuccess() {
		confirmSuccessCounter.increment();
	}

	public void incrementConfirmFailure(String reason, int httpStatus, String errorCode) {
		Counter.builder("payment_confirm_failure_total")
			.description("결제 승인 실패 건수")
			.tag("reason", reason)
			.tag("http_status", String.valueOf(httpStatus))
			.tag("error_code", errorCode)
			.register(meterRegistry)
			.increment();
	}
}
