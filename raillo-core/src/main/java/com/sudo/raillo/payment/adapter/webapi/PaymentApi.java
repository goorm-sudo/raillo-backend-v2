package com.sudo.raillo.payment.adapter.webapi;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.common.response.SuccessResponse;
import com.sudo.raillo.payment.adapter.webapi.dto.PaymentConfirmRequest;
import com.sudo.raillo.payment.adapter.webapi.dto.PaymentConfirmResponse;
import com.sudo.raillo.payment.adapter.webapi.dto.PaymentPrepareRequest;
import com.sudo.raillo.payment.adapter.webapi.dto.PaymentPrepareResponse;
import com.sudo.raillo.payment.application.PaymentConfirmResult;
import com.sudo.raillo.payment.application.PaymentPrepareResult;
import com.sudo.raillo.payment.application.provided.PaymentConfirmer;
import com.sudo.raillo.payment.application.provided.PaymentPreparer;
import com.sudo.raillo.payment.domain.success.PaymentSuccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Payments", description = "결제 API")
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentApi {

	private final PaymentPreparer paymentPreparer;
	private final PaymentConfirmer paymentConfirmer;

	@Operation(summary = "결제 준비", description = "Redis의 예약(PendingBooking) 목록을 바탕으로 주문(Order)과 결제(Payment)를 생성합니다. "
		+ "토스페이먼츠 결제 위젯 초기화에 필요한 orderId와 amount를 반환합니다.")
	@PostMapping("/prepare")
	public SuccessResponse<PaymentPrepareResponse> preparePayment(
		@RequestBody @Valid PaymentPrepareRequest request,
		@AuthenticationPrincipal UserDetails userDetails) {
		String memberNo = userDetails.getUsername();
		PaymentPrepareResult result = paymentPreparer.prepare(request.toCommand(), memberNo);
		return SuccessResponse.of(PaymentSuccess.PAYMENT_PREPARE_SUCCESS, PaymentPrepareResponse.from(result));
	}

	@Operation(summary = "결제 승인", description = "토스페이먼츠 결제 승인 처리를 수행합니다. 클라이언트가 토스로부터 받은 paymentKey, orderId, amount를 전달받아 결제를 승인합니다.")
	@PostMapping("/confirm")
	public SuccessResponse<PaymentConfirmResponse> confirmPayment(
		@RequestBody @Valid PaymentConfirmRequest request,
		@AuthenticationPrincipal UserDetails userDetails) {
		String memberNo = userDetails.getUsername();
		PaymentConfirmResult result = paymentConfirmer.confirm(request.toCommand(), memberNo);
		return SuccessResponse.of(PaymentSuccess.PAYMENT_CONFIRM_SUCCESS, PaymentConfirmResponse.from(result));
	}
}
