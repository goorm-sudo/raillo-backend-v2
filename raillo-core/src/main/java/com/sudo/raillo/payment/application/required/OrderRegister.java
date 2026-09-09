package com.sudo.raillo.payment.application.required;

import java.util.List;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.order.domain.Order;

/**
 * Order 생성 required port.
 *
 * <p>결제 유스케이스에서 Order를 새로 등록할 때만 사용한다.
 * 조회/검증은 {@link OrderReader}에 분리되어 있다.
 */
public interface OrderRegister {

	Order createOrder(String memberNo, List<PendingBooking> pendingBookings);
}
