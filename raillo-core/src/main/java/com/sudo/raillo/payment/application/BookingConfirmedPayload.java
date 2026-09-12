package com.sudo.raillo.payment.application;

import java.util.List;

import com.sudo.raillo.booking.domain.PendingBooking;

/**
 * OutboxWorker가 결제 성공 후 Redis 정리에 참조할 최소 정보.
 *
 * <p>실제 처리 로직은 새 Redis 아키텍처 확정 후 도입 예정이므로,
 * 이 payload 스키마는 향후 확장될 수 있다.
 */
public record BookingConfirmedPayload(List<Entry> pendingBookings) {

	public record Entry(
		String pendingBookingId,
		String memberNo,
		Long trainScheduleId,
		Long departureStopId,
		Long arrivalStopId,
		List<Long> seatIds
	) {}

	public static BookingConfirmedPayload from(List<PendingBooking> pendingBookings) {
		List<Entry> entries = pendingBookings.stream()
			.map(pb -> new Entry(
				pb.getId(),
				pb.getMemberNo(),
				pb.getTrainScheduleId(),
				pb.getDepartureStopId(),
				pb.getArrivalStopId(),
				pb.getSeatIds()
			))
			.toList();
		return new BookingConfirmedPayload(entries);
	}
}
