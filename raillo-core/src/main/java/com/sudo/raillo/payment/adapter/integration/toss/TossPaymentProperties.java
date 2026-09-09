package com.sudo.raillo.payment.adapter.integration.toss;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "payment.toss.api")
public record TossPaymentProperties(
	@NotBlank String baseUrl,
	@NotBlank String clientKey,
	@NotBlank String secretKey,
	String version
) {
	public TossPaymentProperties {
		if (secretKey != null && secretKey.startsWith("${")) {
			throw new IllegalStateException(
				"TOSS_SECRET_KEY 환경변수가 설정되지 않았습니다. .env 파일을 확인하세요."
			);
		}
	}
}
