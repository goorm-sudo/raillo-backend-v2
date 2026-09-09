package com.sudo.raillo.booking.presentation;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.booking.application.dto.ReservationCreateCommand;
import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.booking.application.facade.ReservationFacade;
import com.sudo.raillo.booking.docs.ReservationControllerDoc;
import com.sudo.raillo.booking.success.BookingSuccess;
import com.sudo.raillo.common.response.SuccessResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ReservationController implements ReservationControllerDoc {
	private final ReservationFacade reservationFacade;

	@PostMapping("/api/v1/reservations")
	public SuccessResponse<ReservationCreateResponse> create(
		@AuthenticationPrincipal UserDetails userDetails,
		@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
		@Valid @RequestBody ReservationCreateRequest request
	) {
		return SuccessResponse.of(BookingSuccess.RESERVATION_CREATE_SUCCESS,
			reservationFacade.create(new ReservationCreateCommand(userDetails.getUsername(), idempotencyKey, request)));
	}
}
