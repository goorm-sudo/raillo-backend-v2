package com.sudo.raillo.payment.adapter.persistence;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentStatus;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentPersistenceAdapter implements PaymentRepository {

	private final PaymentJpaRepository jpaRepository;

	@Override
	public Payment save(Payment payment) {
		return jpaRepository.save(payment);
	}

	@Override
	public Optional<Payment> findById(Long paymentId) {
		return jpaRepository.findById(paymentId);
	}

	@Override
	public Optional<Payment> findByPaymentKey(String paymentKey) {
		return jpaRepository.findByPaymentKey(paymentKey);
	}

	@Override
	public Optional<Payment> findByOrder(Order order) {
		return jpaRepository.findByOrder(order);
	}

	@Override
	public boolean existsByOrderAndPaymentStatus(Order order, PaymentStatus status) {
		return jpaRepository.existsByOrderAndPaymentStatus(order, status);
	}
}
