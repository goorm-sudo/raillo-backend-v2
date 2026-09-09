package com.sudo.raillo.payment.adapter.integration;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.required.BookingCreator;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BookingCreatorAdapter implements BookingCreator {

	private final BookingService bookingService;

	@Override
	public void createBookingFromOrder(Order order) {
		bookingService.createBookingFromOrder(order);
	}
}
