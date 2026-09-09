package com.sudo.raillo.booking.application.dto.projection;

import com.querydsl.core.annotations.QueryProjection;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.train.domain.type.CarType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Getter;

@Getter
public class ReceiptProjection {

	private final String ticketNumber;
	private final LocalDateTime ticketCreatedAt;
	private final String trainNumber;
	private final int carNumber;
	private final CarType carType;
	private final String seatNumber;
	private final LocalDate operationDate;
	private final String departureStationName;
	private final String arrivalStationName;
	private final LocalTime departureTime;
	private final LocalTime arrivalTime;
	private final PassengerType passengerType;
	private final PaymentMethod paymentMethod;
	private final LocalDateTime paidAt;
	private final String paymentKey;
	private final BigDecimal amount;

	@QueryProjection
	public ReceiptProjection(
		String ticketNumber,
		LocalDateTime ticketCreatedAt,
		String trainNumber,
		int carNumber,
		CarType carType,
		String seatNumber,
		LocalDate operationDate,
		String departureStationName,
		String arrivalStationName,
		LocalTime departureTime,
		LocalTime arrivalTime,
		PassengerType passengerType,
		PaymentMethod paymentMethod,
		LocalDateTime paidAt,
		String paymentKey,
		BigDecimal amount
	) {
		this.ticketNumber = ticketNumber;
		this.ticketCreatedAt = ticketCreatedAt;
		this.trainNumber = trainNumber;
		this.carNumber = carNumber;
		this.carType = carType;
		this.seatNumber = seatNumber;
		this.operationDate = operationDate;
		this.departureStationName = departureStationName;
		this.arrivalStationName = arrivalStationName;
		this.departureTime = departureTime;
		this.arrivalTime = arrivalTime;
		this.passengerType = passengerType;
		this.paymentMethod = paymentMethod;
		this.paidAt = paidAt;
		this.paymentKey = paymentKey;
		this.amount = amount;
	}
}
