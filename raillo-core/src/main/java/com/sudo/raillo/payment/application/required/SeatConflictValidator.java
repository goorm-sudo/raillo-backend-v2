package com.sudo.raillo.payment.application.required;

import java.util.List;

import com.sudo.raillo.booking.domain.PendingBooking;

/**
 * 결제 준비 시 PendingBooking 간 좌석 충돌 검증 required port.
 */
public interface SeatConflictValidator {

	void validateSeatConflicts(List<PendingBooking> pendingBookings);
}
