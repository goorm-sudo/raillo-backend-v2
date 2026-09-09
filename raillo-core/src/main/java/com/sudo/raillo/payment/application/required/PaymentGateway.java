package com.sudo.raillo.payment.application.required;

import java.math.BigDecimal;

import com.sudo.raillo.payment.application.PaymentConfirmCommand;
import com.sudo.raillo.payment.domain.PaymentMethod;

/**
 * 외부 결제 게이트웨이 required port.
 *
 * <p>토스페이먼츠 등 PG 세부사항은 어댑터에 격리하고, 애플리케이션은 도메인 중립 결과만 다룬다.
 */
public interface PaymentGateway {

	GatewayConfirmResult confirm(PaymentConfirmCommand command);

	record GatewayConfirmResult(
		String paymentKey,
		String orderCode,
		BigDecimal totalAmount,
		PaymentMethod method
	) {
	}
}
