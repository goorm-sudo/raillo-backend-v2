package com.sudo.raillo.payment.adapter.observability;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sudo.raillo.payment.adapter.observability.TossApiMetrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class TossApiMetricsTest {

	private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
	private final TossApiMetrics tossApiMetrics = new TossApiMetrics(meterRegistry);

	@Test
	@DisplayName("실패 시 toss_api_failure_total 카운터가 태그별로 증가한다")
	void incrementFailure_incrementsCounterWithTags() {
		// when
		tossApiMetrics.incrementFailure("confirm", 400, "INVALID_REQUEST");
		tossApiMetrics.incrementFailure("confirm", 400, "INVALID_REQUEST");
		tossApiMetrics.incrementFailure("confirm", 500, "INTERNAL_ERROR");

		// then
		double invalidRequestCount = meterRegistry.counter("toss_api_failure_total",
			"operation", "confirm",
			"http_status", "400",
			"toss_code", "INVALID_REQUEST").count();
		assertThat(invalidRequestCount).isEqualTo(2);

		double internalErrorCount = meterRegistry.counter("toss_api_failure_total",
			"operation", "confirm",
			"http_status", "500",
			"toss_code", "INTERNAL_ERROR").count();
		assertThat(internalErrorCount).isEqualTo(1);
	}

	@Test
	@DisplayName("cancel operation 실패 시 카운터가 별도로 증가한다")
	void incrementFailure_cancelOperation_incrementsSeparately() {
		// when
		tossApiMetrics.incrementFailure("confirm", 400, "INVALID_REQUEST");
		tossApiMetrics.incrementFailure("cancel", 400, "INVALID_REQUEST");

		// then
		double confirmCount = meterRegistry.counter("toss_api_failure_total",
			"operation", "confirm",
			"http_status", "400",
			"toss_code", "INVALID_REQUEST").count();
		double cancelCount = meterRegistry.counter("toss_api_failure_total",
			"operation", "cancel",
			"http_status", "400",
			"toss_code", "INVALID_REQUEST").count();
		assertThat(confirmCount).isEqualTo(1);
		assertThat(cancelCount).isEqualTo(1);
	}

	@Test
	@DisplayName("tossCode가 null이면 UNKNOWN으로 정규화된다")
	void incrementFailure_nullTossCode_normalizedToUnknown() {
		// when
		tossApiMetrics.incrementFailure("confirm", 500, null);

		// then
		double count = meterRegistry.counter("toss_api_failure_total",
			"operation", "confirm",
			"http_status", "500",
			"toss_code", "UNKNOWN").count();
		assertThat(count).isEqualTo(1);
	}
}
