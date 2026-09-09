package com.sudo.raillo.payment.application.required;

import java.util.Optional;

import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentStatus;

/**
 * Payment 영속화 required port.
 *
 * <p>Spring Data JPA 등 특정 기술에 의존하지 않는 순수 인터페이스로 둔다.
 */
public interface PaymentRepository {

	Payment save(Payment payment);

	Optional<Payment> findById(Long paymentId);

	Optional<Payment> findByPaymentKey(String paymentKey);

	Optional<Payment> findByOrder(Order order);

	boolean existsByOrderAndPaymentStatus(Order order, PaymentStatus status);
}
