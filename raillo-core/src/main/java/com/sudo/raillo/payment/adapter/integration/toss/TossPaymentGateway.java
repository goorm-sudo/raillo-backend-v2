package com.sudo.raillo.payment.adapter.integration.toss;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.payment.application.PaymentConfirmCommand;
import com.sudo.raillo.payment.application.required.PaymentGateway;
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossPaymentGateway implements PaymentGateway {

	private final TossPaymentClient tossPaymentClient;

	@Override
	public GatewayConfirmResult confirm(PaymentConfirmCommand command) {
		TossPaymentConfirmResponse response = tossPaymentClient.confirmPayment(command);
		return new GatewayConfirmResult(
			response.paymentKey(),
			response.orderId(),
			BigDecimal.valueOf(response.totalAmount()),
			mapMethod(response.method())
		);
	}

	private PaymentMethod mapMethod(String tossMethod) {
		return switch (tossMethod) {
			case "카드" -> PaymentMethod.CREDIT_CARD;
			case "가상계좌" -> PaymentMethod.VIRTUAL_ACCOUNT;
			case "계좌이체" -> PaymentMethod.TRANSFER;
			case "휴대폰" -> PaymentMethod.MOBILE_PHONE;
			case "간편결제" -> PaymentMethod.EASY_PAY;
			case "문화상품권", "도서문화상품권", "게임문화상품권" -> PaymentMethod.GIFT_CERTIFICATE;
			default -> {
				log.warn("알 수 없는 결제수단: {}", tossMethod);
				throw new BusinessException(PaymentError.INVALID_PAYMENT_METHOD,
					"지원하지 않는 결제 수단입니다: " + tossMethod);
			}
		};
	}
}
