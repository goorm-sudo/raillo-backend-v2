package com.sudo.raillo.payment.application.provided;

import com.sudo.raillo.payment.application.PaymentConfirmCommand;
import com.sudo.raillo.payment.application.PaymentConfirmResult;

/**
 * 결제 승인 유스케이스 provided port.
 *
 * <p>토스페이먼츠 승인 API를 호출하고 Order/Booking 확정, 좌석 Hold 해제까지 오케스트레이션한다.
 */
public interface PaymentConfirmer {

	PaymentConfirmResult confirm(PaymentConfirmCommand command, String memberNo);
}
