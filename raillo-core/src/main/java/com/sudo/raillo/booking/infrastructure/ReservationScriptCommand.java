package com.sudo.raillo.booking.infrastructure;

import java.util.List;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.domain.type.CarType;

public record ReservationScriptCommand(
	String trainScheduleId, String memberNo, String idempotencyKey, String requestHash,
	String reservationId, String departureStationId, String arrivalStationId,
	List<RequestedSeat> seats, long holdDurationMillis, long salesCutoffMillis, PriceSnapshot price
) {
	public record RequestedSeat(String seatId, PassengerType passengerType) {
	}

	public record PriceSnapshot(String version, String generation, List<PricedSeat> seats, String totalFare) {
		public record PricedSeat(String seatId, PassengerType passengerType, CarType carType, String fare) {
		}
	}

	public ReservationScriptCommand withPrice(PriceSnapshot snapshot) {
		return new ReservationScriptCommand(trainScheduleId, memberNo, idempotencyKey, requestHash,
			reservationId, departureStationId, arrivalStationId, seats, holdDurationMillis, salesCutoffMillis, snapshot);
	}
}
