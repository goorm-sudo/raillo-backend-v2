package com.sudo.raillo.payment.application.required;

import java.util.List;

import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;

/**
 * Order 조회/소유자 검증 required port.
 *
 * <p>결제 유스케이스에서 Order를 읽거나 소유자를 확인할 때만 사용한다.
 * 쓰기(생성)는 {@link OrderRegister}에 분리되어 있다.
 */
public interface OrderReader {

	Order getOrderByOrderCode(String orderCode);

	List<String> getPendingBookingIds(Order order);

	void validateOrderOwner(Order order, Member member);
}
