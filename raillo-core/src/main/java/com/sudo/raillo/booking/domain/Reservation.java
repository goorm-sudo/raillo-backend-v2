package com.sudo.raillo.booking.domain;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.sudo.raillo.booking.domain.status.ReservationStatus;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
	getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class Reservation {

	private String id;

	private String memberNo;

	private Long trainScheduleId;

	private Long departureStopId;

	private Long arrivalStopId;

	private List<SeatReservation> seatReservations;

	private BigDecimal totalFare;

	private int departureStopOrder;
	private int arrivalStopOrder;
	private ReservationStatus status;
	private int schemaVersion;
	private long version;
	private String generation;
	private String fareVersion;
	private String requestHash;
	// Redis TIME 기준 UTC Unix timestamp(ms).
	private long createdAt;
	private long expiresAt;

	/** 구 주문/결제 fixture의 전환용. 새 예약은 create_reservation.lua 결과로만 생성한다. */
	@Deprecated
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
		reservation.seatReservations = List.copyOf(seatReservations);
		reservation.totalFare = totalFare;
		reservation.createdAt = System.currentTimeMillis();
		return reservation;
	}

	public List<SeatReservation> getSeatReservations() {
		return seatReservations == null ? List.of() : List.copyOf(seatReservations);
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
