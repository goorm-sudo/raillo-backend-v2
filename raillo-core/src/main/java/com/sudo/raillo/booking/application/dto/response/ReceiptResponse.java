package com.sudo.raillo.booking.application.dto.response;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.train.domain.type.CarType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Schema(description = "영수증 조회 응답 DTO")
public record ReceiptResponse(

	@Schema(description = "승차권 번호")
	String ticketNumber,

	@Schema(description = "발행 일시", example = "2026-01-01")
	LocalDateTime ticketCreatedAt,

	@Schema(description = "열차 번호", example = "027")
	String trainNumber,

	@Schema(description = "객차 번호", example = "1")
	int carNumber,

	@Schema(description = "객차 타입 (STANDARD=일반실, FIRST_CLASS=특실)", example = "STANDARD")
	CarType carType,

	@Schema(description = "좌석 번호 (행 + 열)", example = "1D")
	String seatNumber,

	@Schema(description = "운행일", example = "2026-01-01")
	LocalDate operationDate,

	@Schema(description = "출발역명", example = "서울")
	String departureStationName,

	@Schema(description = "도착역명", example = "부산")
	String arrivalStationName,

	@Schema(description = "출발 시간", example = "11:58")
	LocalTime departureTime,

	@Schema(description = "도착 시간", example = "12:38")
	LocalTime arrivalTime,

	@Schema(description = "승객 유형", example = "ADULT")
	PassengerType passengerType,

	@Schema(description = "결제 수단", example = "CARD")
	PaymentMethod paymentMethod,

	@Schema(description = "승인 일자", example = "2026-01-01")
	LocalDateTime paidAt,

	@Schema(description = "승인 번호", example = "2026-01-01")
	String paymentKey,

	@Schema(description = "결제 금액", example = "50000")
	BigDecimal amount
) {
}
