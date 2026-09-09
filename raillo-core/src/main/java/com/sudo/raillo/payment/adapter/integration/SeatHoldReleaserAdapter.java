package com.sudo.raillo.payment.adapter.integration;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.service.SeatHoldService;
import com.sudo.raillo.payment.application.required.SeatHoldReleaser;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SeatHoldReleaserAdapter implements SeatHoldReleaser {

	private final SeatHoldService seatHoldService;

	@Override
	public void releaseSeats(
		String pendingBookingId,
		Long trainScheduleId,
		List<Long> seatIds,
		Long trainCarId,
		int departureStopOrder,
		int arrivalStopOrder
	) {
		seatHoldService.releaseSeats(pendingBookingId, trainScheduleId, seatIds, trainCarId, departureStopOrder, arrivalStopOrder);
	}
}
