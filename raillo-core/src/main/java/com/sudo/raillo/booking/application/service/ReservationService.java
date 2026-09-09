package com.sudo.raillo.booking.application.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.calculator.ReservationFareCalculator;
import com.sudo.raillo.booking.application.dto.ReservationCreateCommand;
import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.ReservationRedisRepository;
import com.sudo.raillo.booking.infrastructure.ReservationScriptCommand;
import com.sudo.raillo.booking.infrastructure.ReservationScriptCommand.PriceSnapshot;
import com.sudo.raillo.booking.infrastructure.ReservationScriptResult;
import com.sudo.raillo.booking.util.ReservationIdGenerator;
import com.sudo.raillo.common.exception.BusinessException;

import jakarta.validation.Validator;

@Service
// Redis 요청에서 JPA 트랜잭션/DB connection을 시작하지 않는다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ReservationService {
	private final ReservationRedisRepository repository;
	private final ReservationFareCalculator calculator;
	private final ReservationIdGenerator idGenerator;
	private final Validator validator;
	private final long holdDurationMillis;
	private final long salesCutoffMillis;

	public ReservationService(ReservationRedisRepository repository, ReservationFareCalculator calculator,
		ReservationIdGenerator idGenerator, Validator validator,
		@Value("${reservation.hold-duration:10m}") Duration holdDuration,
		@Value("${reservation.sales-cutoff:0s}") Duration salesCutoff) {
		this.repository = repository;
		this.calculator = calculator;
		this.idGenerator = idGenerator;
		this.validator = validator;
		this.holdDurationMillis = holdDuration.toMillis();
		this.salesCutoffMillis = salesCutoff.toMillis();
		if (holdDurationMillis <= 0 || holdDurationMillis > Duration.ofDays(1).toMillis()
			|| salesCutoffMillis < 0 || salesCutoffMillis > Duration.ofDays(1).toMillis()) {
			throw new IllegalArgumentException("예약 유효기간과 판매 마감 설정은 1일 이내여야 합니다.");
		}
	}

	public Reservation create(ReservationCreateCommand command) {
		validate(command);
		ReservationCreateRequest request = command.request();
		List<ReservationScriptCommand.RequestedSeat> seats = request.seats().stream()
			.sorted(Comparator.comparing(ReservationCreateRequest.SeatRequest::seatId))
			.map(seat -> new ReservationScriptCommand.RequestedSeat(seat.seatId().toString(), seat.passengerType()))
			.toList();
		ReservationScriptCommand input = new ReservationScriptCommand(request.trainScheduleId().toString(),
			command.memberNo(), command.idempotencyKey(), requestHash(request, seats), idGenerator.generate(),
			request.departureStationId().toString(), request.arrivalStationId().toString(), seats,
			holdDurationMillis, salesCutoffMillis, null);

		for (int attempt = 0; attempt < 3; attempt++) {
			// 첫 실행도 같은 Lua로 멱등성을 먼저 확인한다. 재시도에는 운임 재계산이 필요 없다.
			ReservationScriptResult result = repository.execute(input);
			if ("QUOTE".equals(result.code())) {
				result = repository.execute(input.withPrice(price(result.quote())));
			}
			if ("CREATED".equals(result.code()) || "REPLAY".equals(result.code())) {
				if (result.reservation() == null) {
					throw new BusinessException(BookingError.RESERVATION_RESULT_UNKNOWN);
				}
				return result.reservation();
			}
			if (!"REFERENCE_CHANGED".equals(result.code())) {
				throw new BusinessException(error(result.code()));
			}
		}
		throw new BusinessException(BookingError.RESERVATION_REFERENCE_CHANGED);
	}

	private void validate(ReservationCreateCommand command) {
		if (command == null || command.request() == null
			|| command.memberNo() == null || !command.memberNo().matches("[A-Za-z0-9_-]{1,64}")
			|| command.idempotencyKey() == null || !command.idempotencyKey().matches("[A-Za-z0-9_-]{1,128}")
			|| !validator.validate(command.request()).isEmpty()) {
			throw new BusinessException(BookingError.INVALID_RESERVATION_REQUEST);
		}
		ReservationCreateRequest request = command.request();
		if (request.departureStationId().equals(request.arrivalStationId())
			|| request.seats().stream().map(ReservationCreateRequest.SeatRequest::seatId).distinct().count()
			!= request.seats().size()) {
			throw new BusinessException(BookingError.INVALID_RESERVATION_REQUEST);
		}
	}

	private PriceSnapshot price(ReservationScriptResult.Quote quote) {
		if (quote == null || quote.seats() == null || quote.seats().isEmpty()) {
			throw new BusinessException(BookingError.RESERVATION_STATE_INCONSISTENT);
		}
		List<PriceSnapshot.PricedSeat> seats = quote.seats().stream().map(seat ->
			new PriceSnapshot.PricedSeat(seat.seatId(), seat.passengerType(), seat.carType(),
				calculator.calculate(seat.baseFare(), seat.passengerType()).toPlainString())).toList();
		BigDecimal total = seats.stream().map(seat -> new BigDecimal(seat.fare()))
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		return new PriceSnapshot(quote.version(), quote.generation(), seats, total.toPlainString());
	}

	private String requestHash(ReservationCreateRequest request, List<ReservationScriptCommand.RequestedSeat> seats) {
		StringBuilder canonical = new StringBuilder("v1|").append(request.trainScheduleId()).append('|')
			.append(request.departureStationId()).append('|').append(request.arrivalStationId());
		seats.forEach(seat -> canonical.append('|').append(seat.seatId()).append(':').append(seat.passengerType()));
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}
	}

	private BookingError error(String code) {
		return switch (code) {
			case "INVALID_REQUEST" -> BookingError.INVALID_RESERVATION_REQUEST;
			case "NOT_READY" -> BookingError.RESERVATION_REFERENCE_NOT_READY;
			case "SALES_CLOSED" -> BookingError.RESERVATION_SALES_CLOSED;
			case "IDEMPOTENCY_CONFLICT" -> BookingError.RESERVATION_IDEMPOTENCY_CONFLICT;
			case "ID_CONFLICT" -> BookingError.RESERVATION_ID_CONFLICT;
			case "SEAT_CONFLICT" -> BookingError.SEAT_CONFLICT_WITH_HOLD;
			case "SOLD_CONFLICT" -> BookingError.SEAT_CONFLICT_WITH_SOLD;
			case "INVALID_CAR_TYPE" -> BookingError.INVALID_CAR_TYPE;
			case "INCONSISTENT" -> BookingError.RESERVATION_STATE_INCONSISTENT;
			default -> BookingError.RESERVATION_RESULT_UNKNOWN;
		};
	}
}
