package com.sudo.raillo.payment.adapter.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.PaymentConfirmResult;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentStatus;
import jakarta.persistence.LockModeType;

@Repository
public interface PaymentJpaRepository extends JpaRepository<Payment, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Payment p where p.id = :paymentId")
	Optional<Payment> findByIdForUpdate(@Param("paymentId") Long paymentId);

	@Query("""
		select new com.sudo.raillo.payment.application.PaymentConfirmResult(
			p.id, p.orderCode, p.paymentKey, p.amount, p.paymentMethod, p.paymentStatus, p.paidAt)
		from Payment p where p.id = :paymentId
		""")
	Optional<PaymentConfirmResult> findConfirmResultById(@Param("paymentId") Long paymentId);

	/**
	 * 결제 키로 결제 정보 조회
	 */
	Optional<Payment> findByPaymentKey(String paymentKey);

	Optional<Payment> findByOrder(Order order);

	boolean existsByOrderAndPaymentStatus(Order order, PaymentStatus status);
}
