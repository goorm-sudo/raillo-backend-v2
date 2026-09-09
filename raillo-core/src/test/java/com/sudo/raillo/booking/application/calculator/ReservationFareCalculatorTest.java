package com.sudo.raillo.booking.application.calculator;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.sudo.raillo.booking.domain.type.PassengerType;

class ReservationFareCalculatorTest {
	private final ReservationFareCalculator calculator = new ReservationFareCalculator();

	@ParameterizedTest
	@CsvSource({"ADULT,10001", "CHILD,6000.6", "INFANT,2500.25", "SENIOR,7000.7",
		"DISABLED_HEAVY,5000.5", "DISABLED_LIGHT,7000.7", "VETERAN,5000.5"})
	@DisplayName("승객 유형별로 기존 할인율을 적용하고 소수 운임을 손실 없이 계산한다.")
	void calculate_discount_without_rounding(PassengerType passengerType, String expected) {
		// given
		BigDecimal baseFare = new BigDecimal("10001");
		// when
		BigDecimal fare = calculator.calculate(baseFare, passengerType);
		// then
		assertThat(fare).isEqualByComparingTo(expected);
	}
}
