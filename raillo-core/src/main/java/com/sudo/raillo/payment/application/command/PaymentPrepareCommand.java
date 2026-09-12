package com.sudo.raillo.payment.application.command;

import java.util.List;

public record PaymentPrepareCommand(
	List<String> pendingBookingIds
) {
}
