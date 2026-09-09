package com.sudo.raillo.payment.application.provided;

import com.sudo.raillo.payment.application.PaymentPrepareCommand;
import com.sudo.raillo.payment.application.PaymentPrepareResult;

/**
 * 결제 준비 유스케이스 provided port.
 *
 * <p>PendingBooking으로부터 Order와 Payment를 생성하고 토스 위젯 초기화에 필요한
 * orderId와 amount를 반환한다. 응답 DTO 변환은 어댑터(컨트롤러) 책임이다.
 */
public interface PaymentPreparer {

	PaymentPrepareResult prepare(PaymentPrepareCommand command, String memberNo);
}
