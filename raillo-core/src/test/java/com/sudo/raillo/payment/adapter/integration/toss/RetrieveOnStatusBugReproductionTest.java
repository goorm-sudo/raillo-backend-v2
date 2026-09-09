package com.sudo.raillo.payment.adapter.integration.toss;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;
import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.payment.application.PaymentConfirmCommand;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.payment.domain.exception.TossPaymentException;
import com.sudo.raillo.payment.adapter.integration.toss.TossErrorResponseV1;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentConfirmResponse;


/**
 * retrieve() + onStatus() 버그 재현 테스트
 *
 * <h2>검증 가설</h2>
 * 빈 오류 body를 역직렬화하면 TossPaymentException 대신 일반 예외 경로로 처리된다.
 * Jackson 3은 역직렬화 예외를 runtime exception으로 던지므로, 이전 Spring의
 * IOException wrapping 메시지에 의존하지 않고 애플리케이션 예외 계약만 검증한다.
 *
 * <h2>시나리오</h2>
 * <ol>
 *   <li>정상 JSON body → TossPaymentException 전파 (retrieve+onStatus 자체는 문제없음)</li>
 *   <li>빈 body + no Content-Type → BusinessException</li>
 *   <li>빈 body + application/json → Content-Type과 무관하게 body가 없으면 동일하게 실패</li>
 * </ol>
 *
 */
@DisplayName("retrieve() + onStatus(): 빈 오류 body 처리")
class RetrieveOnStatusBugReproductionTest {

	private MockRestServiceServer server;
	private LegacyTossConfirmClient legacyClient;

	/**
	 * 버그가 있던 retrieve() + onStatus() 방식 클라이언트.
	 * #167 수정 전 TossPaymentClient.confirmPayment()를 그대로 재현.
	 */
	static class LegacyTossConfirmClient {

		private final RestClient restClient;
		private final ObjectMapper objectMapper;

		LegacyTossConfirmClient(RestClient restClient, ObjectMapper objectMapper) {
			this.restClient = restClient;
			this.objectMapper = objectMapper;
		}

		TossPaymentConfirmResponse confirmPayment(PaymentConfirmCommand request) {
			try {
				return restClient.post()
					.uri("/v1/payments/confirm")
					.body(request)
					.retrieve()
					.onStatus(HttpStatusCode::isError, (req, res) -> handleError(res))
					.body(TossPaymentConfirmResponse.class);
			} catch (TossPaymentException e) {
				throw e;
			} catch (Exception e) {
				throw new BusinessException(
					PaymentError.PAYMENT_SYSTEM_ERROR,
					"결제 승인 처리 중 알 수 없는 오류: " + e.getMessage()
				);
			}
		}

		private void handleError(ClientHttpResponse res) throws IOException {
			// 버그의 핵심 지점: body가 비어있으면 readAllBytes()→""→Jackson 파싱 실패
			String raw = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
			TossErrorResponseV1 error = objectMapper.readValue(raw, TossErrorResponseV1.class);
			throw new TossPaymentException(res.getStatusCode().value(), error.code(), error.message());
		}
	}

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.baseUrl("https://api.tosspayments.com").build();
		legacyClient = new LegacyTossConfirmClient(restClient, new ObjectMapper());
	}

	@Nested
	@DisplayName("시나리오 1: 정상 JSON body가 있을 때")
	class Scenario1_ProperJsonBody {

		@Test
		@DisplayName("4xx + JSON body → TossPaymentException 정상 전파 (retrieve+onStatus 자체는 문제없음)")
		void properJsonBody_tossPaymentException_propagates() {
			// given
			PaymentConfirmCommand request = new PaymentConfirmCommand("pk", "oid", BigDecimal.valueOf(1000));
			String errorBody = """
				{"code": "UNAUTHORIZED_KEY", "message": "인증되지 않은 시크릿 키"}
				""";

			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andRespond(
					withStatus(HttpStatus.UNAUTHORIZED)
						.body(errorBody)
						.contentType(MediaType.APPLICATION_JSON)
				);

			// when & then
			// handleError() → TossPaymentException(RuntimeException)
			// RuntimeException은 Spring의 catch(IOException)에 걸리지 않음 → 정상 전파
			assertThatThrownBy(() -> legacyClient.confirmPayment(request))
				.isInstanceOf(TossPaymentException.class)
				.hasFieldOrPropertyWithValue("errorCode", "UNAUTHORIZED_KEY")
				.hasMessageContaining("인증되지 않은 시크릿 키");

			server.verify();
		}
	}

	@Nested
	@DisplayName("시나리오 2: 빈 body + Content-Type 없음 (실제 운영 버그 재현)")
	class Scenario2_EmptyBodyNoContentType {

		@Test
		@DisplayName("4xx + 빈 body + no Content-Type → TossPaymentException 대신 BusinessException 발생")
		void emptyBodyNoContentType_businessException_not_tossPaymentException() {
			// given
			PaymentConfirmCommand request = new PaymentConfirmCommand("pk", "oid", BigDecimal.valueOf(1000));

			// Content-Type 없음 → Spring getContentType() = null → APPLICATION_OCTET_STREAM 기본값
			// body 없음 → readAllBytes() = [] → Jackson 파싱 실패 → MismatchedInputException(IOException)
			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andRespond(withStatus(HttpStatus.UNAUTHORIZED));

			// when & then
			// Jackson 역직렬화 실패 → catch(Exception e) → BusinessException
			assertThatThrownBy(() -> legacyClient.confirmPayment(request))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_SYSTEM_ERROR)
				.hasMessageContaining("결제 승인 처리 중 알 수 없는 오류");

			server.verify();
		}
	}

	@Nested
	@DisplayName("시나리오 3: 빈 body + Content-Type 있음")
	class Scenario3_EmptyBodyWithContentType {

		@Test
		@DisplayName("4xx + 빈 body + application/json → Content-Type과 무관하게 BusinessException 발생")
		void emptyBodyWithContentType_still_businessException() {
			// given
			PaymentConfirmCommand request = new PaymentConfirmCommand("pk", "oid", BigDecimal.valueOf(1000));

			// Content-Type은 있지만 body가 없음
			server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
				.andExpect(method(POST))
				.andRespond(
					withStatus(HttpStatus.UNAUTHORIZED)
						.contentType(MediaType.APPLICATION_JSON)
				);

			// when & then
			// body가 없으면 Content-Type이 application/json이어도 동일하게 실패
			assertThatThrownBy(() -> legacyClient.confirmPayment(request))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_SYSTEM_ERROR)
				.hasMessageContaining("결제 승인 처리 중 알 수 없는 오류");

			server.verify();
		}
	}

}
