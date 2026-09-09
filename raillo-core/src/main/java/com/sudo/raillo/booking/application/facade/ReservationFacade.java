package com.sudo.raillo.booking.application.facade;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.dto.ReservationCreateCommand;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.booking.application.service.ReservationService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ReservationFacade {
	private final ReservationService reservationService;

	public ReservationCreateResponse create(ReservationCreateCommand command) {
		return ReservationCreateResponse.from(reservationService.create(command));
	}
}
