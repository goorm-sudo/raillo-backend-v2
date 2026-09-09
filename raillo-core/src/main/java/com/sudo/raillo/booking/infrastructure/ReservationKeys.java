package com.sudo.raillo.booking.infrastructure;

import java.util.List;

public record ReservationKeys(long scheduleId) {
	public ReservationKeys {
		if (scheduleId <= 0) {
			throw new IllegalArgumentException("운행 일정 ID는 양수여야 합니다.");
		}
	}

	public String key(String suffix) {
		return "rail:{schedule:" + scheduleId + "}:" + suffix;
	}

	public List<String> creationKeys() {
		return List.of(key("meta"), key("stops"), key("seats"), key("fares"),
			key("reservations"), key("occupancy"), key("deadlines"), key("idempotency"));
	}
}
