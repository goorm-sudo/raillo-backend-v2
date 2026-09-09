package com.sudo.raillo.payment.adapter.observability;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class TossApiMetrics {

	private final MeterRegistry meterRegistry;

	public TossApiMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	public void incrementFailure(String operation, int httpStatus, String tossCode) {
		Counter.builder("toss_api_failure_total")
			.description("Toss API 호출 실패 건수")
			.tag("operation", operation)
			.tag("http_status", String.valueOf(httpStatus))
			.tag("toss_code", tossCode != null ? tossCode : "UNKNOWN")
			.register(meterRegistry)
			.increment();
	}
}
