package com.sudo.raillo.booking.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

	private String id;

	private String memberNo;

	private Long trainScheduleId;

 	private Long departureStopId;

	private Long arrivalStopId;

	private List<SeatReservation> seatReservations;

	private BigDecimal totalFare;

	private LocalDateTime createdAt;

	public static Reservation create(
		String id,
		String memberNo,
		Long trainScheduleId,
		Long departureStopId,
		Long arrivalStopId,
		List<SeatReservation> seatReservations,
		BigDecimal totalFare
	) {
		Reservation reservation = new Reservation();
		reservation.id = id;
		reservation.memberNo = memberNo;
		reservation.trainScheduleId = trainScheduleId;
		reservation.departureStopId = departureStopId;
		reservation.arrivalStopId = arrivalStopId;
		reservation.seatReservations = seatReservations;
		reservation.totalFare = totalFare;
		reservation.createdAt = LocalDateTime.now();
		return reservation;
	}

	public List<Long> getSeatIds() {
		if (seatReservations == null) {
			return List.of();
		}

		return seatReservations.stream()
			.map(SeatReservation::seatId)
			.toList();
	}
}
