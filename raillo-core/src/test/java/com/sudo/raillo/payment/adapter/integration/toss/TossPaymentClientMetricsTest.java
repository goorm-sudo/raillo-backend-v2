package com.sudo.raillo.payment.adapter.integration.toss;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sudo.raillo.payment.application.PaymentConfirmCommand;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentCancelRequest;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentClient;
import com.sudo.raillo.payment.adapter.observability.TossApiMetrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@RestClientTest(TossPaymentClient.class)
@Import(TossPaymentClientMetricsTest.TestConfig.class)
class TossPaymentClientMetricsTest {

	private static final String SECRET_KEY = "test_sk_secret_key";

	static class TestConfig {
		@Bean
		public RestClient tossPaymentRestClient(RestClient.Builder restClientBuilder) {
			String encodedSecretKey = Base64.getEncoder()
				.encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8));

			return restClientBuilder
				.baseUrl("https://api.tosspayments.com")
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedSecretKey)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.build();
		}

		@Bean
		public MeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}

		@Bean
		public TossApiMetrics tossApiMetrics(MeterRegistry meterRegistry) {
			return new TossApiMetrics(meterRegistry);
		}
	}

	@Autowired
	private TossPaymentClient tossPaymentClient;

	@Autowired
	private MockRestServiceServer server;

	@Autowired
	private MeterRegistry meterRegistry;

	@Nested
	@DisplayName("confirmPayment 메트릭")
	class ConfirmPaymentMetrics {

		@Test
		@DisplayName("4xx 응답 시 toss_api_failure_total 카운터가 증가한다")
		void fail_4xx_incrementsFailureCounter() {
			// given
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			String errorBody = """
				{
					"code": "REJECT_CARD_PAYMENT",
					"message": "카드 결제가 거절되었습니다."
				}
				""";

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andRespond(withBadRequest().body(errorBody).contentType(MediaType.APPLICATION_JSON));

			double before = meterRegistry.counter("toss_api_failure_total",
				"operation", "confirm", "http_status", "400", "toss_code", "REJECT_CARD_PAYMENT").count();

			// when
			assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request));

			// then
			double after = meterRegistry.counter("toss_api_failure_total",
				"operation", "confirm", "http_status", "400", "toss_code", "REJECT_CARD_PAYMENT").count();
			assertThat(after).isEqualTo(before + 1);

			server.verify();
		}

		@Test
		@DisplayName("5xx 응답 시 toss_api_failure_total 카운터가 증가한다")
		void fail_5xx_incrementsFailureCounter() {
			// given
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			String errorBody = """
				{
					"code": "PROVIDER_ERROR",
					"message": "일시적인 오류가 발생했습니다."
				}
				""";

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andRespond(withServerError().body(errorBody).contentType(MediaType.APPLICATION_JSON));

			double before = meterRegistry.counter("toss_api_failure_total",
				"operation", "confirm", "http_status", "500", "toss_code", "PROVIDER_ERROR").count();

			// when
			assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request));

			// then
			double after = meterRegistry.counter("toss_api_failure_total",
				"operation", "confirm", "http_status", "500", "toss_code", "PROVIDER_ERROR").count();
			assertThat(after).isEqualTo(before + 1);

			server.verify();
		}

		@Test
		@DisplayName("5xx 응답 본문이 비어 있을 때 EMPTY_ERROR_BODY toss_api_failure_total 카운터가 증가한다")
		void fail_5xx_emptyBody_incrementsFailureCounter() {
			// given
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andRespond(withServerError());

			double before = meterRegistry.counter("toss_api_failure_total",
				"operation", "confirm", "http_status", "500", "toss_code", "EMPTY_ERROR_BODY").count();

			// when
			assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request));

			// then
			double after = meterRegistry.counter("toss_api_failure_total",
				"operation", "confirm", "http_status", "500", "toss_code", "EMPTY_ERROR_BODY").count();
			assertThat(after).isEqualTo(before + 1);

			server.verify();
		}

		@Test
		@DisplayName("예상치 못한 예외 발생 시 CLIENT_ERROR toss_api_failure_total 카운터가 증가한다")
		void fail_unexpectedException_incrementsFailureCounter() {
			// given
			PaymentConfirmCommand request = new PaymentConfirmCommand(
				"toss_pk_123", "ORDER_001", BigDecimal.valueOf(50000));

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

			double before = meterRegistry.counter("toss_api_failure_total",
				"operation", "confirm", "http_status", "0", "toss_code", "CLIENT_ERROR").count();

			// when
			assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request));

			// then
			double after = meterRegistry.counter("toss_api_failure_total",
				"operation", "confirm", "http_status", "0", "toss_code", "CLIENT_ERROR").count();
			assertThat(after).isEqualTo(before + 1);

			server.verify();
		}
	}

	@Nested
	@DisplayName("cancelPayment 메트릭")
	class CancelPaymentMetrics {

		@Test
		@DisplayName("예상치 못한 예외 발생 시 CLIENT_ERROR toss_api_failure_total 카운터가 증가한다")
		void fail_unexpectedException_incrementsFailureCounter() {
			// given
			String paymentKey = "toss_pk_cancel_123";
			TossPaymentCancelRequest request = new TossPaymentCancelRequest("고객 변심", null);

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/" + paymentKey + "/cancel"))
				.andExpect(method(POST))
				.andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

			double before = meterRegistry.counter("toss_api_failure_total",
				"operation", "cancel", "http_status", "0", "toss_code", "CLIENT_ERROR").count();

			// when
			assertThatThrownBy(() -> tossPaymentClient.cancelPayment(paymentKey, request));

			// then
			double after = meterRegistry.counter("toss_api_failure_total",
				"operation", "cancel", "http_status", "0", "toss_code", "CLIENT_ERROR").count();
			assertThat(after).isEqualTo(before + 1);

			server.verify();
		}
	}
}
