package com.sudo.raillo.payment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentMethod {
	CREDIT_CARD("카드", "카드 결제"),
	VIRTUAL_ACCOUNT("가상계좌", "가상계좌 입금"),
	TRANSFER("계좌이체", "계좌이체 결제"),
	MOBILE_PHONE("휴대폰", "휴대폰 소액결제"),
	GIFT_CERTIFICATE("상품권", "상품권 결제"),
	EASY_PAY("간편결제", "카카오페이, 네이버페이 등"),
	UNKNOWN("기타", "알 수 없는 결제 수단");

	private final String displayName;
	private final String description;
}
