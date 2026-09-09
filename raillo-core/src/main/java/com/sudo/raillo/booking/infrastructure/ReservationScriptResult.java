package com.sudo.raillo.booking.infrastructure;

import java.math.BigDecimal;
import java.util.List;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.domain.type.CarType;

public record ReservationScriptResult(String code, Reservation reservation, Quote quote) {
	public record Quote(String version, String generation, List<QuotedSeat> seats) {
		public record QuotedSeat(String seatId, PassengerType passengerType, CarType carType, BigDecimal baseFare) {
		}
	}
}
