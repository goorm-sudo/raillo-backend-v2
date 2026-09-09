package com.sudo.raillo.payment.application.required;

import java.util.List;

/**
 * 좌석 Hold 해제 required port.
 */
public interface SeatHoldReleaser {

	void releaseSeats(
		String pendingBookingId,
		Long trainScheduleId,
		List<Long> seatIds,
		Long trainCarId,
		int departureStopOrder,
		int arrivalStopOrder
	);
}
