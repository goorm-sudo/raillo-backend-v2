package com.sudo.raillo.payment.adapter.integration;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.payment.application.required.PendingBookingReader;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PendingBookingReaderAdapter implements PendingBookingReader {

	private final PendingBookingService pendingBookingService;

	@Override
	public List<Reservation> getPendingBookings(List<String> pendingBookingIds, String memberNo) {
		return pendingBookingService.getPendingBookings(pendingBookingIds, memberNo);
	}

	@Override
	public void deletePendingBookings(List<String> pendingBookingIds, String memberNo) {
		pendingBookingService.deletePendingBookings(pendingBookingIds, memberNo);
	}
}
