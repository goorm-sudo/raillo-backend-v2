package com.sudo.raillo.booking.application.dto.response;

import java.math.BigDecimal;
import java.util.List;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.SeatReservation;
import com.sudo.raillo.booking.domain.status.ReservationStatus;

public record ReservationCreateResponse(
	String reservationId, Long trainScheduleId, ReservationStatus status,
	List<SeatReservation> seats, BigDecimal totalFare, long createdAt, long expiresAt
) {
	public static ReservationCreateResponse from(Reservation reservation) {
		return new ReservationCreateResponse(reservation.getId(), reservation.getTrainScheduleId(),
			reservation.getStatus(), reservation.getSeatReservations(), reservation.getTotalFare(),
			reservation.getCreatedAt(), reservation.getExpiresAt());
	}
}
