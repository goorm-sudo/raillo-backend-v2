package com.sudo.raillo.payment.application.required;

import com.sudo.raillo.order.domain.Order;

/**
 * 결제 승인 시 Booking 확정 required port.
 */
public interface BookingCreator {

	void createBookingFromOrder(Order order);
}
