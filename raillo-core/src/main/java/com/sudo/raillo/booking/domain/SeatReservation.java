package com.sudo.raillo.booking.domain;

import java.math.BigDecimal;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.domain.type.CarType;

public record SeatReservation(
	Long seatId,
	PassengerType passengerType,
	CarType carType,
	BigDecimal fare
) {
	// 구 주문/결제 fixture 전환 전의 소스 호환용. 새 생성 경로에서는 운임을 반드시 저장한다.
	public SeatReservation(Long seatId, PassengerType passengerType) {
		this(seatId, passengerType, null, null);
	}
}
