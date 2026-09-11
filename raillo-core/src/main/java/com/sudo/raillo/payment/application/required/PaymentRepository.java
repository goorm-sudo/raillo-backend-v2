package com.sudo.raillo.payment.application.required;

import java.util.Optional;

import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.PaymentConfirmResult;
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

	/** 승인 시도 생성과 paymentKey 저장을 직렬화한다. 호출 트랜잭션이 종료될 때 잠금을 해제한다. */
	Optional<Payment> findByIdForUpdate(Long paymentId);

	/** 이미 로딩한 엔티티 대신 DB의 현재 커밋 상태로 승인 결과를 조회한다. */
	Optional<PaymentConfirmResult> findConfirmResultById(Long paymentId);

	Optional<Payment> findByPaymentKey(String paymentKey);

	Optional<Payment> findByOrder(Order order);

	boolean existsByOrderAndPaymentStatus(Order order, PaymentStatus status);
}
