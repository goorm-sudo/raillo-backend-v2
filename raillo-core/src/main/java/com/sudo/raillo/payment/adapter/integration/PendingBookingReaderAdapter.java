package com.sudo.raillo.payment.adapter.integration;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.service.PendingBookingService;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.payment.application.required.PendingBookingReader;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PendingBookingReaderAdapter implements PendingBookingReader {

	private final PendingBookingService pendingBookingService;

	@Override
	public List<PendingBooking> getPendingBookings(List<String> pendingBookingIds, String memberNo) {
		return pendingBookingService.getPendingBookings(pendingBookingIds, memberNo);
	}

	@Override
	public void deletePendingBookings(List<String> pendingBookingIds, String memberNo) {
		pendingBookingService.deletePendingBookings(pendingBookingIds, memberNo);
	}
}
