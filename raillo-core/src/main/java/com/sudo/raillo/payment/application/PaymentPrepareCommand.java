package com.sudo.raillo.payment.application;

import java.util.List;

public record PaymentPrepareCommand(
	List<String> pendingBookingIds
) {
}
