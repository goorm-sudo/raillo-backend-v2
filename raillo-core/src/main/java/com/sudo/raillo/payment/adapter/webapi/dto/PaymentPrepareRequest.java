package com.sudo.raillo.payment.adapter.webapi.dto;

import java.util.List;

import com.sudo.raillo.payment.application.PaymentPrepareCommand;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

@Schema(defaultValue = "결제 준비 요청")
public record PaymentPrepareRequest(

	@Schema(defaultValue = "예약 ID 목록 (Redis에 저장된 PendingBooking의 ID - UUID")
	@NotEmpty(message = "예약 ID 목록은 필수입니다")
	@Size(min = 1, max = 5, message = "한 번에 최대 5개의 예약까지 결제 가능합니다")
	List<@NotBlank(message = "예약 ID는 공백일 수 없습니다") String> pendingBookingIds
) {
	public PaymentPrepareCommand toCommand() {
		return new PaymentPrepareCommand(pendingBookingIds);
	}
}
