package com.sudo.raillo.payment.adapter.integration;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.payment.application.required.SeatConflictValidator;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SeatConflictValidatorAdapter implements SeatConflictValidator {

	private final BookingValidator bookingValidator;

	@Override
	public void validateSeatConflicts(List<Reservation> reservations) {
		bookingValidator.validateSeatConflicts(reservations);
	}
}
