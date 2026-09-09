package com.sudo.raillo.payment.application.required;

import java.util.List;

import com.sudo.raillo.booking.domain.Reservation;

/**
 * PendingBooking 도메인 접점 required port.
 */
public interface PendingBookingReader {

	List<Reservation> getPendingBookings(List<String> pendingBookingIds, String memberNo);

	void deletePendingBookings(List<String> pendingBookingIds, String memberNo);
}
