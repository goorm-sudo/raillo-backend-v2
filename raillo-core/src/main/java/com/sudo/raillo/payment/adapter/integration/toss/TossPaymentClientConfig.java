package com.sudo.raillo.payment.adapter.integration.toss;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableConfigurationProperties(TossPaymentProperties.class)
public class TossPaymentClientConfig {

	@Bean
	public RestClient tossPaymentRestClient(TossPaymentProperties properties) {
		String encodedSecretKey = Base64.getEncoder()
			.encodeToString((properties.secretKey() + ":").getBytes(StandardCharsets.UTF_8));

		return RestClient.builder()
			.baseUrl(properties.baseUrl())
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedSecretKey)
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.requestInterceptor((request, body, execution) -> {
				log.debug("[TOSS] → {} {}", request.getMethod(), request.getURI());
				log.debug("[TOSS] → headers: {}", maskSensitiveHeaders(request.getHeaders()));
				log.debug("[TOSS] → body: {}", new String(body, StandardCharsets.UTF_8));
				return execution.execute(request, body);
			})
			.build();
	}

	private Map<String, List<String>> maskSensitiveHeaders(HttpHeaders headers) {
		Map<String, List<String>> masked = new LinkedHashMap<>();
		headers.forEach((name, values) -> masked.put(name, List.copyOf(values)));
		if (masked.containsKey(HttpHeaders.AUTHORIZATION)) {
			masked.put(HttpHeaders.AUTHORIZATION, List.of("Basic ***"));
		}
		return masked;
	}
}
