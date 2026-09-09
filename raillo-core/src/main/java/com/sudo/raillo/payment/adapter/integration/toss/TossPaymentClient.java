package com.sudo.raillo.payment.adapter.integration.toss;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;
import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.payment.adapter.integration.toss.TossErrorResponseV1;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentCancelResponse;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentConfirmResponse;
import com.sudo.raillo.payment.adapter.integration.toss.TossPaymentCancelRequest;
import com.sudo.raillo.payment.adapter.observability.TossApiMetrics;
import com.sudo.raillo.payment.application.PaymentConfirmCommand;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.payment.domain.exception.TossPaymentException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossPaymentClient {

	private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private final RestClient tossPaymentRestClient;
	private final ObjectMapper objectMapper;
	private final TossApiMetrics tossApiMetrics;

	/**
	 * 토스페이먼츠 결제 승인 API 호출
	 *
	 * @param command 결제 승인 커맨드 (paymentKey, orderId, amount)
	 * @return Payment 객체 -> TossPaymentConfirmResponse 변환
	 * <ul>
	 * 	   <li>성공: 200 OK + Payment 객체</li>
	 *     <li>실패: 4xx, 5xx 에러</li>
	 * </ul>
	 */
	public TossPaymentConfirmResponse confirmPayment(PaymentConfirmCommand command) {
		log.info("토스 결제 승인 요청: paymentKey={}, orderId={}, amount={}",
			command.paymentKey(), command.orderId(), command.amount());

		try {
			TossPaymentConfirmResponse response = tossPaymentRestClient.post()
				.uri("/v1/payments/confirm")
				.body(command)
				.exchange((req, res) -> {
					if (res.getStatusCode().isError()) {
						handleErrorResponse(res, "confirm");
					}
					return res.bodyTo(TossPaymentConfirmResponse.class);
				});

			log.info("[TOSS] 결제 승인 성공: paymentKey={}, orderId={}, status={}",
				response.paymentKey(), response.orderId(), response.status());

			return response;

		} catch (TossPaymentException e) {
			throw e;
		} catch (Exception e) {
			log.error("[TOSS] 결제 승인 중 알 수 없는 예외 발생", e);
			// http_status=0: HTTP 응답을 정상적으로 수신하지 못한 경우 (타임아웃, 네트워크 오류, 응답 파싱 실패 등)
			tossApiMetrics.incrementFailure("confirm", 0, "CLIENT_ERROR");
			throw new BusinessException(
				PaymentError.PAYMENT_SYSTEM_ERROR,
				"결제 승인 처리 중 알 수 없는 오류가 발생했습니다: " + e.getMessage()
			);
		}
	}

	/**
	 * 토스페이먼츠 결제 취소 API 호출
	 *
	 * @param paymentKey 결제 키
	 * @param request 취소 요청 (cancelReason 필수, cancelAmount는 부분 취소 시에만)
	 * @return Payment 객체 -> TossPaymentCancelResponse 변환
	 *
	 * <h4>멱등키(Idempotency-Key) 적용 방법</h4>
	 * <p>현재는 사용하지 않지만, 재시도 로직이나 배치 실패 복구가 필요할 경우 적용 가능</p>
	 * <pre>{@code
	 * // 요청 시 헤더 추가
	 * .header("Idempotency-Key", UUID.randomUUID().toString())
	 *
	 * // 같은 멱등키로 재시도하면 토스가 캐시된 응답 반환 (중복 취소 방지)
	 * // 멱등키는 15일간 유효
	 * }</pre>
	 */
	public TossPaymentCancelResponse cancelPayment(String paymentKey, TossPaymentCancelRequest request) {
		String idempotencyKey = generateIdempotencyKey();

		log.info("[TOSS] 결제 취소 요청: paymentKey={}, cancelReason={}, cancelAmount={}, idempotencyKey={}",
			paymentKey, request.cancelReason(), request.cancelAmount(), idempotencyKey);

		try {
			TossPaymentCancelResponse response = tossPaymentRestClient.post()
				.uri("/v1/payments/{paymentKey}/cancel", paymentKey)
				.header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
				.body(request)
				.exchange((req, res) -> {
					if (res.getStatusCode().isError()) {
						handleErrorResponse(res, "cancel");
					}
					return res.bodyTo(TossPaymentCancelResponse.class);
				});

			log.info("[TOSS] 결제 취소 성공: paymentKey={}, status={}, balanceAmount={}, cancelCount={}",
				response.paymentKey(), response.status(), response.balanceAmount(), response.getCancelCount());

			return response;

		} catch (TossPaymentException e) {
			throw e;
		} catch (Exception e) {
			log.error("[TOSS] 결제 취소 중 알 수 없는 예외 발생", e);
			// http_status=0: HTTP 응답을 정상적으로 수신하지 못한 경우 (타임아웃, 네트워크 오류, 응답 파싱 실패 등)
			tossApiMetrics.incrementFailure("cancel", 0, "CLIENT_ERROR");
			throw new BusinessException(
				PaymentError.PAYMENT_SYSTEM_ERROR,
				"결제 취소 처리 중 알 수 없는 오류가 발생했습니다: " + e.getMessage()
			);
		}
	}

	private String generateIdempotencyKey() {
		return UUID.randomUUID().toString();
	}

	private void handleErrorResponse(ClientHttpResponse res, String operation) throws IOException {
		int statusCode = res.getStatusCode().value();

		byte[] rawBytes = res.getBody().readAllBytes();
		String raw = new String(rawBytes, StandardCharsets.UTF_8);

		log.info("[TOSS] {} 에러 응답: httpStatus={}, Content-Type={}, bytes={}, traceId={}",
			operation, statusCode,
			res.getHeaders().getContentType(),
			rawBytes.length,
			res.getHeaders().getFirst("x-tosspayments-trace-id"));

		if (rawBytes.length == 0) {
			String message = "토스 에러 응답 본문이 비어 있습니다. (httpStatus=" + statusCode + ")";
			if (res.getStatusCode().is5xxServerError()) {
				log.error("[TOSS] {} 실패 ({}): {}", operation, statusCode, message);
			} else {
				log.warn("[TOSS] {} 실패 ({}): {}", operation, statusCode, message);
			}
			tossApiMetrics.incrementFailure(operation, statusCode, "EMPTY_ERROR_BODY");
			throw new TossPaymentException(statusCode, "EMPTY_ERROR_BODY", message);
		}

		TossErrorResponseV1 error;
		try {
			error = objectMapper.readValue(raw, TossErrorResponseV1.class);
		} catch (RuntimeException e) {
			String bodySnippet = truncateForLog(raw);
			String message = "토스 에러 응답 파싱 실패 (httpStatus=" + statusCode + ")";
			log.error("[TOSS] {} 실패 ({}): {} bodySnippet={}", operation, statusCode, message, bodySnippet, e);
			tossApiMetrics.incrementFailure(operation, statusCode, "UNPARSABLE_ERROR_BODY");
			throw new TossPaymentException(statusCode, "UNPARSABLE_ERROR_BODY", message + ", body=" + bodySnippet);
		}

		if (res.getStatusCode().is5xxServerError()) {
			log.error("[TOSS] {} 실패 (5xx): httpStatus={}, code={}, message={}",
				operation, statusCode, error.code(), error.message());
		} else {
			log.warn("[TOSS] {} 실패 (4xx): httpStatus={}, code={}, message={}",
				operation, statusCode, error.code(), error.message());
		}

		tossApiMetrics.incrementFailure(operation, statusCode, error.code());
		throw new TossPaymentException(statusCode, error.code(), error.message());
	}

	private String truncateForLog(String raw) {
		String normalized = raw.replaceAll("\\s+", " ").trim();
		if (normalized.length() <= 500) {
			return normalized;
		}
		return normalized.substring(0, 500) + "...(truncated)";
	}
}
