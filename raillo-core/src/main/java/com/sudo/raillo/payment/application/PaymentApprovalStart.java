package com.sudo.raillo.payment.application;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.payment.application.result.PaymentConfirmResult;
import java.util.List;

/**
 * 승인 시작 단계의 결과. 새 승인을 진행하거나, 같은 attemptId의 이전 승인 결과를 그대로 돌려준다.
 *
 * <p>{@link #isAlreadyConfirmed()}가 참이면 Toss를 호출하지 않고 {@link #previousResult()}로 응답한다.
 */
public record PaymentApprovalStart(
	Long paymentId,
	Long attemptDbId,
	List<PendingBooking> pendingBookings,
	PaymentConfirmResult previousResult
) {

	public static PaymentApprovalStart started(Long paymentId, Long attemptDbId, List<PendingBooking> pendingBookings) {
		return new PaymentApprovalStart(paymentId, attemptDbId, pendingBookings, null);
	}

	public static PaymentApprovalStart alreadyConfirmed(PaymentConfirmResult previousResult) {
		return new PaymentApprovalStart(null, null, null, previousResult);
	}

	public boolean isAlreadyConfirmed() {
		return previousResult != null;
	}
}
