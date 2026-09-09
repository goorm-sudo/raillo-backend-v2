package com.sudo.raillo.payment.adapter.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentStatus;

@Repository
public interface PaymentJpaRepository extends JpaRepository<Payment, Long> {

	/**
	 * 결제 키로 결제 정보 조회
	 */
	Optional<Payment> findByPaymentKey(String paymentKey);

	Optional<Payment> findByOrder(Order order);

	boolean existsByOrderAndPaymentStatus(Order order, PaymentStatus status);
}
