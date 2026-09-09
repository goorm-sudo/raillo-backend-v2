package com.sudo.raillo.booking.application.calculator;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.domain.type.PassengerType;

@Component
public class ReservationFareCalculator {
	/** 기존 운임 정책과 같이 할인 후 추가 반올림을 하지 않는다. */
	public BigDecimal calculate(BigDecimal baseFare, PassengerType passengerType) {
		if (baseFare == null || baseFare.signum() < 0 || passengerType == null) {
			throw new IllegalArgumentException("운임과 승객 유형이 올바르지 않습니다.");
		}
		BigDecimal rate = switch (passengerType) {
			case ADULT -> BigDecimal.ONE;
			case CHILD -> new BigDecimal("0.6");
			case INFANT -> new BigDecimal("0.25");
			case SENIOR, DISABLED_LIGHT -> new BigDecimal("0.7");
			case DISABLED_HEAVY, VETERAN -> new BigDecimal("0.5");
		};
		return baseFare.multiply(rate);
	}
}
