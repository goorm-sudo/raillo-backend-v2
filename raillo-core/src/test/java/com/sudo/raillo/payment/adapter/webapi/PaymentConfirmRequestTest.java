package com.sudo.raillo.payment.adapter.webapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.sudo.raillo.payment.adapter.webapi.dto.PaymentConfirmRequest;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

class PaymentConfirmRequestTest {

	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

	@AfterAll
	static void closeFactory() {
		FACTORY.close();
	}

	@Test
	@DisplayName("attemptId가 64자이면 승인 요청 검증을 통과한다")
	void accepts_attempt_id_at_length_limit() {
		// given
		PaymentConfirmRequest request = request("a".repeat(64));

		// when / then
		assertThat(FACTORY.getValidator().validate(request)).isEmpty();
	}

	@Test
	@DisplayName("attemptId가 65자이면 승인 요청 검증에 실패한다")
	void rejects_attempt_id_over_length_limit() {
		// given
		PaymentConfirmRequest request = request("a".repeat(65));

		// when / then
		assertThat(FACTORY.getValidator().validate(request))
			.singleElement().satisfies(violation -> {
				assertThat(violation.getPropertyPath().toString()).isEqualTo("attemptId");
				assertThat(violation.getMessage()).isEqualTo("attemptId는 64자 이하여야 합니다");
			});
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " ")
	@DisplayName("attemptId가 없거나 공백이면 서버 파생 키를 사용할 수 있다")
	void accepts_missing_attempt_id(String attemptId) {
		// given
		PaymentConfirmRequest request = request(attemptId);

		// when / then
		assertThat(FACTORY.getValidator().validate(request)).isEmpty();
		assertThat(request.toCommand().attemptIdOrDerived()).hasSize(64);
	}

	private PaymentConfirmRequest request(String attemptId) {
		return new PaymentConfirmRequest("payment-key", "order-code", BigDecimal.valueOf(10000), attemptId);
	}
}
