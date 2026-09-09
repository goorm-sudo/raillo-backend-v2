package com.sudo.raillo.payment.adapter.integration;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.order.application.OrderService;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.required.OrderRegister;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OrderRegisterAdapter implements OrderRegister {

	private final OrderService orderService;

	@Override
	public Order createOrder(String memberNo, List<PendingBooking> pendingBookings) {
		return orderService.createOrder(memberNo, pendingBookings);
	}
}
