package com.sudo.raillo.booking.domain;

import com.sudo.raillo.booking.domain.type.PassengerType;

public record SeatReservation(
	Long seatId,
	PassengerType passengerType
) {
}
