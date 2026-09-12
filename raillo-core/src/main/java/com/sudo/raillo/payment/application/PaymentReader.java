package com.sudo.raillo.payment.application;

import com.sudo.raillo.payment.application.result.PaymentConfirmResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import lombok.RequiredArgsConstructor;

/**
 * Payment 조회 전용 컴포넌트.
 *
 * <p>{@link PaymentModifier}가 상태 전이·저장을 담당한다면 여기서는 읽기만 담당한다.
 * 상태를 바꾸지 않는 유스케이스는 이 클래스를 통해 조회한다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentReader {

	private final PaymentRepository paymentRepository;

	public Payment getPaymentByOrder(Order order) {
		return paymentRepository.findByOrder(order)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));
	}

	/**
	 * 이미 로드한 Payment 엔티티가 아니라 DB의 최신 커밋 상태로 승인 결과를 조회한다.
	 * 재요청 처리 시 이전 조회에서 얻은 분리된 엔티티 상태를 응답에 사용하지 않기 위해 사용한다.
	 */
	public PaymentConfirmResult getConfirmResult(Long paymentId) {
		return paymentRepository.findConfirmResultById(paymentId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));
	}
}
