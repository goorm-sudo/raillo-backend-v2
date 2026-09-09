package com.sudo.raillo.booking.util;

import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class ReservationIdGenerator {

	public String generate() {
		return "res_" + UUID.randomUUID();
	}
}
