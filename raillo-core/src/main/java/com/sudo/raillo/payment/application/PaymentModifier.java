package com.sudo.raillo.payment.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Payment 애그리게이트 상태 전이 오케스트레이션.
 *
 * <p>결제 준비/승인 유스케이스가 공유하는 내부 도메인 서비스. provided port로 노출하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentModifier {

	private final PaymentRepository paymentRepository;

	public Payment createPayment(Member member, Order order) {
		Payment payment = Payment.create(member, order);
		Payment saved = paymentRepository.save(payment);
		log.info("[결제 생성] paymentId={}, orderId={}, amount={}", saved.getId(), order.getId(), order.getTotalAmount());
		return saved;
	}

	@Transactional(readOnly = true)
	public Payment getPaymentByOrder(Order order) {
		return paymentRepository.findByOrder(order)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));
	}

	/**
	 * PaymentKey 저장을 별도 트랜잭션으로 커밋한다. 토스 호출 전 무조건 반영되어야 하므로 REQUIRES_NEW.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updatePaymentKeyInNewTransaction(Long paymentId, String paymentKey) {
		Payment payment = paymentRepository.findById(paymentId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));
		payment.updatePaymentKey(paymentKey);
	}

	/**
	 * 결제 실패 정보를 별도 트랜잭션으로 저장한다. 외부 게이트웨이 실패 시 반드시 반영되어야 하므로 REQUIRES_NEW.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void failPaymentInNewTransaction(Long paymentId, String failureCode, String failureMessage) {
		Payment payment = paymentRepository.findById(paymentId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));
		payment.fail(failureCode, failureMessage);
	}
}
