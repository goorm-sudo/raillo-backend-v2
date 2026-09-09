package com.sudo.raillo.payment.adapter.integration;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.application.OrderService;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.required.OrderReader;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OrderReaderAdapter implements OrderReader {

	private final OrderService orderService;

	@Override
	public Order getOrderByOrderCode(String orderCode) {
		return orderService.getOrderByOrderCode(orderCode);
	}

	@Override
	public List<String> getPendingBookingIds(Order order) {
		return orderService.getPendingBookingIds(order);
	}

	@Override
	public void validateOrderOwner(Order order, Member member) {
		orderService.validateOrderOwner(order, member);
	}
}
