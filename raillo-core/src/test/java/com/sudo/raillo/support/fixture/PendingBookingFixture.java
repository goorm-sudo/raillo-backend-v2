package com.sudo.raillo.support.fixture;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.SeatReservation;
import com.sudo.raillo.booking.domain.type.PassengerType;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PendingBookingFixture {

	private String id = UUID.randomUUID().toString();
	private String memberNo = "202601010001";
	private Long trainScheduleId = 1L;
	private Long departureStopId = 1L;
	private Long arrivalStopId = 2L;
	private List<SeatReservation> seatReservations = List.of(new SeatReservation(1L, PassengerType.ADULT));
	private BigDecimal totalFare = BigDecimal.ZERO;

	public static Reservation create() {
		return builder().build();
	}

	// builder method
	public static PendingBookingFixture builder() {
		return new PendingBookingFixture();
	}

	public Reservation build() {
		return Reservation.create(
			id,
			memberNo,
			trainScheduleId,
			departureStopId,
			arrivalStopId,
			seatReservations,
			totalFare
		);
	}

	public PendingBookingFixture withId(String id) {
		this.id = id;
		return this;
	}

	public PendingBookingFixture withMemberNo(String memberNo) {
		this.memberNo = memberNo;
		return this;
	}

	public PendingBookingFixture withTrainScheduleId(Long trainScheduleId) {
		this.trainScheduleId = trainScheduleId;
		return this;
	}

	public PendingBookingFixture withDepartureStopId(Long departureStopId) {
		this.departureStopId = departureStopId;
		return this;
	}

	public PendingBookingFixture withArrivalStopId(Long arrivalStopId) {
		this.arrivalStopId = arrivalStopId;
		return this;
	}

	public PendingBookingFixture withSeatReservations(List<SeatReservation> seatReservations) {
		this.seatReservations = seatReservations;
		return this;
	}

	public PendingBookingFixture withTotalFare(BigDecimal totalFare) {
		this.totalFare = totalFare;
		return this;
	}
}
