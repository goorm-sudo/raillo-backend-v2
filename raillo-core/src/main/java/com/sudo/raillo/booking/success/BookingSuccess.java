package com.sudo.raillo.booking.success;

import org.springframework.http.HttpStatus;

import com.sudo.raillo.common.response.SuccessCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BookingSuccess implements SuccessCode {

	// 예약 관련
	RESERVATION_CREATE_SUCCESS(HttpStatus.CREATED, "예약이 성공적으로 생성되었습니다."),
	PENDING_BOOKING_CREATE_SUCCESS(HttpStatus.CREATED, "예약이 성공적으로 생성되었습니다."),
	PENDING_BOOKING_LIST_SUCCESS(HttpStatus.OK, "예약 목록을 성공적으로 조회했습니다."),
	PENDING_BOOKING_DELETE_SUCCESS(HttpStatus.NO_CONTENT, "예약이 성공적으로 삭제되었습니다."),

	// 예매 & 승차권 관련
	BOOKING_CREATE_SUCCESS(HttpStatus.CREATED, "예매가 성공적으로 생성되었습니다."),
	BOOKING_DELETE_SUCCESS(HttpStatus.NO_CONTENT, "예매가 성공적으로 삭제되었습니다."),
	BOOKING_LIST_SUCCESS(HttpStatus.OK, "승차권을 포함한 예매 목록을 성공적으로 조회했습니다."),
	BOOKING_DETAIL_SUCCESS(HttpStatus.OK, "승차권을 포함한 예매 상세를 성공적으로 조회했습니다."),
	RECEIPT_SUCCESS(HttpStatus.OK, "영수증을 성공적으로 조회했습니다.");

	private final HttpStatus status;
	private final String message;
}
