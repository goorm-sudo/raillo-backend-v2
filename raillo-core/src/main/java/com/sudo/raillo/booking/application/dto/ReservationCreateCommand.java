package com.sudo.raillo.booking.application.dto;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;

public record ReservationCreateCommand(
	String memberNo,
	String idempotencyKey,
	ReservationCreateRequest request
) {
}
