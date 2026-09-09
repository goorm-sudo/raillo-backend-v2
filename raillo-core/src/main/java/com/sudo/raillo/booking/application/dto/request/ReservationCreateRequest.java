package com.sudo.raillo.booking.application.dto.request;

import java.util.List;

import com.sudo.raillo.booking.domain.type.PassengerType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ReservationCreateRequest(
	@NotNull @Positive Long trainScheduleId,
	@NotNull @Positive Long departureStationId,
	@NotNull @Positive Long arrivalStationId,
	@NotNull @Size(min = 1, max = 8) List<@NotNull @Valid SeatRequest> seats
) {
	public record SeatRequest(@NotNull @Positive Long seatId, @NotNull PassengerType passengerType) {
	}
}
