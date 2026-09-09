package com.sudo.raillo.booking.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.sudo.raillo.booking.application.dto.ReservationCreateCommand;
import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest.SeatRequest;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.status.ReservationStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.ReservationKeys;
import com.sudo.raillo.booking.infrastructure.ReservationRedisRepository;
import com.sudo.raillo.booking.infrastructure.ReservationScriptCommand;
import com.sudo.raillo.booking.infrastructure.ReservationScriptCommand.PriceSnapshot;
import com.sudo.raillo.booking.support.ReservationTestConfig;
import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.container.TestContainerInitializer;
import com.sudo.raillo.train.domain.type.CarType;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@ServiceTest
@ContextConfiguration(classes = ReservationTestConfig.class, initializers = TestContainerInitializer.class)
class ReservationServiceTest {
	@Autowired private ReservationService service;
	@MockitoSpyBean private ReservationRedisRepository repository;
	@Autowired private StringRedisTemplate redis;
	@MockitoSpyBean private DataSource dataSource;
	@LocalServerPort private int port;
	private final ReservationKeys keys = new ReservationKeys(1785);
	private final JsonMapper mapper = JsonMapper.builder().build();
	private long departureAt;

	@BeforeEach
	void set_up() {
		departureAt = System.currentTimeMillis() + 3600000;
		redis.opsForHash().putAll(keys.key("meta"), Map.of("status", "READY", "version", "v1",
			"generation", "g1", "inventoryReady", "1"));
		for (int ordinal = 0; ordinal < 4; ordinal++) {
			redis.opsForHash().put(keys.key("stops"), String.valueOf(ordinal + 1),
				mapper.writeValueAsString(Map.of("id", String.valueOf(101 + ordinal), "ordinal", ordinal,
					"departureAt", departureAt + ordinal * 600000)));
		}
		for (long seatId = 11; seatId <= 14; seatId++) {
			redis.opsForHash().put(keys.key("seats"), String.valueOf(seatId),
				mapper.writeValueAsString(Map.of("carType", seatId == 14 ? "FIRST_CLASS" : "STANDARD", "available", true)));
		}
		for (int from = 1; from < 4; from++) {
			for (int to = from + 1; to <= 4; to++) {
				for (CarType type : CarType.values()) {
					redis.opsForHash().put(keys.key("fares"), from + ":" + to + ":" + type, "10000");
				}
			}
		}
	}

	@Test
	@DisplayName("예약 생성은 DB 연결 없이 운임과 네 가지 Redis 상태를 저장한다.")
	void creates_reservation_without_database_access() throws Exception {
		// given
		var request = new ReservationCreateRequest(1785L, 1L, 3L,
			List.of(new SeatRequest(11L, PassengerType.ADULT), new SeatRequest(12L, PassengerType.CHILD)));
		clearInvocations(dataSource);
		// when
		Reservation reservation = service.create(command("request_1", request));
		// then
		verify(dataSource, never()).getConnection();
		verify(dataSource, never()).getConnection(anyString(), anyString());
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
		assertThat(reservation.getTotalFare()).isEqualByComparingTo("16000");
		assertThat(reservation.getSeatReservations().get(1).fare()).isEqualByComparingTo("6000");
		assertThat(reservation.getExpiresAt() - reservation.getCreatedAt()).isEqualTo(600000);
		assertThat(reservation.getDepartureStopId()).isEqualTo(101L);
		assertThat(reservation.getArrivalStopOrder()).isEqualTo(2);
		assertThat(redis.opsForHash().entries(keys.key("occupancy"))).containsExactlyInAnyOrderEntriesOf(
			Map.of("11:0", reservation.getId(), "11:1", reservation.getId(),
				"12:0", reservation.getId(), "12:1", reservation.getId()));
		assertThat(redis.opsForZSet().score(keys.key("deadlines"), reservation.getId()))
			.isEqualTo((double)reservation.getExpiresAt());
		assertThat(redis.opsForHash().size(keys.key("idempotency"))).isEqualTo(1);
		for (String suffix : List.of("reservations", "occupancy", "deadlines", "idempotency")) {
			assertThat(redis.getExpire(keys.key(suffix))).isEqualTo(-1);
		}
		try (var connection = redis.getConnectionFactory().getConnection()) {
			assertThat(connection.serverCommands().dbSize()).isEqualTo(8);
		}
	}

	@Test
	@DisplayName("HTTP 예약 요청은 인증 회원의 예약을 생성하고 201 응답을 반환한다.")
	void http_creation_uses_authenticated_member() throws Exception {
		// given
		String body = mapper.writeValueAsString(request(1, 3, 11));
		// when
		var response = post(body, "http_request", true);
		// then
		assertThat(response.statusCode()).isEqualTo(201);
		var result = mapper.readTree(response.body()).get("result");
		assertThat(result.get("status").asString()).isEqualTo("HELD");
		assertThat(result.get("expiresAt").asLong()).isGreaterThan(result.get("createdAt").asLong());
		assertThat(stored(result.get("reservationId").asString()).get("memberNo").asString()).isEqualTo("member_1");
	}

	@Test
	@DisplayName("HTTP 요청에 멱등성 키가 없으면 400 응답을 반환한다.")
	void http_missing_idempotency_key_is_bad_request() throws Exception {
		// given
		String body = mapper.writeValueAsString(request(1, 3, 11));
		// when
		var response = post(body, null, true);
		// then
		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).contains(BookingError.INVALID_RESERVATION_REQUEST.getCode());
		assertThat(redis.hasKey(keys.key("reservations"))).isFalse();
	}

	@Test
	@DisplayName("인증되지 않은 HTTP 요청은 예약을 생성하지 않는다.")
	void http_requires_authentication() throws Exception {
		// given
		String body = mapper.writeValueAsString(request(1, 3, 11));
		// when
		var response = post(body, "unauthenticated", false);
		// then
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(redis.hasKey(keys.key("reservations"))).isFalse();
	}

	@Test
	@DisplayName("좌석 목록이 없는 HTTP 요청은 400 응답을 반환한다.")
	void http_rejects_missing_seats() throws Exception {
		// given
		String body = "{\"trainScheduleId\":1785,\"departureStationId\":1,\"arrivalStationId\":3}";
		// when
		var response = post(body, "invalid", true);
		// then
		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(redis.hasKey(keys.key("reservations"))).isFalse();
	}

	@Test
	@DisplayName("Lua의 안전한 정수 범위를 넘는 좌석 식별자도 정밀도를 잃지 않는다.")
	void preserves_long_identifiers() {
		// given
		String seatId = Long.toString(Long.MAX_VALUE);
		redis.opsForHash().put(keys.key("seats"), seatId,
			mapper.writeValueAsString(Map.of("carType", "STANDARD", "available", true)));
		// when
		Reservation reservation = service.create(command("large_id", request(1, 3, Long.MAX_VALUE)));
		// then
		assertThat(reservation.getSeatIds()).containsExactly(Long.MAX_VALUE);
		assertThat(redis.opsForHash().get(keys.key("occupancy"), seatId + ":0")).isEqualTo(reservation.getId());
	}

	@ParameterizedTest
	@ValueSource(strings = {"generation", "fareVersion", "status"})
	@DisplayName("점유 소유자의 세대나 상태 정보가 손상되면 예약을 차단한다.")
	void rejects_corrupt_owner(String field) {
		// given
		Reservation first = service.create(command("first", request(1, 3, 11)));
		update(first.getId(), field, "");
		// when, then
		assertError(command("second", request(1, 3, 11)), BookingError.RESERVATION_STATE_INCONSISTENT);
	}

	@Test
	@DisplayName("같은 좌석의 인접한 비중첩 구간은 각각 예약할 수 있다.")
	void permits_adjacent_sections() {
		// given
		Reservation first = service.create(command("first", request(1, 3, 11)));
		// when
		Reservation next = service.create(command("next", request(3, 4, 11)));
		// then
		assertThat(next.getId()).isNotEqualTo(first.getId());
		assertThat(redis.opsForHash().get(keys.key("occupancy"), "11:2")).isEqualTo(next.getId());
	}

	@Test
	@DisplayName("여러 좌석 중 하나라도 겹치면 빈 좌석과 구간을 포함한 요청 전체를 저장하지 않는다.")
	void rejects_conflict_without_partial_writes() {
		// given
		service.create(command("first", request(1, 3, 12)));
		Map<Object, Object> before = redis.opsForHash().entries(keys.key("occupancy"));
		// when, then
		assertError(command("conflict", request(2, 4, 11, 12)), BookingError.SEAT_CONFLICT_WITH_HOLD);
		assertThat(redis.opsForHash().entries(keys.key("occupancy"))).isEqualTo(before);
		assertThat(redis.opsForHash().size(keys.key("reservations"))).isEqualTo(1);
		assertThat(redis.opsForHash().size(keys.key("idempotency"))).isEqualTo(1);
	}

	@Test
	@DisplayName("좌석 배열 순서만 바꾼 재요청은 운임 변경 후에도 기존 예약과 기한을 반환한다.")
	void replays_same_request_before_reading_fares() {
		// given
		SeatRequest adult = new SeatRequest(11L, PassengerType.ADULT);
		SeatRequest child = new SeatRequest(12L, PassengerType.CHILD);
		Reservation first = service.create(command("same", new ReservationCreateRequest(1785L, 1L, 3L, List.of(adult, child))));
		redis.delete(keys.key("fares"));
		// when
		Reservation replay = service.create(command("same", new ReservationCreateRequest(1785L, 1L, 3L, List.of(child, adult))));
		// then
		assertThat(replay.getId()).isEqualTo(first.getId());
		assertThat(replay.getCreatedAt()).isEqualTo(first.getCreatedAt());
		assertThat(replay.getExpiresAt()).isEqualTo(first.getExpiresAt());
		assertThat(redis.opsForHash().size(keys.key("reservations"))).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 키로 승객 유형을 바꾸면 멱등성 충돌로 거부한다.")
	void rejects_different_payload_with_same_key() {
		// given
		service.create(command("same", request(1, 3, 11)));
		var changed = new ReservationCreateRequest(1785L, 1L, 3L, List.of(new SeatRequest(11L, PassengerType.CHILD)));
		// when, then
		assertError(command("same", changed), BookingError.RESERVATION_IDEMPOTENCY_CONFLICT);
	}

	@Test
	@DisplayName("같은 키도 다른 회원의 요청이면 별도 예약으로 처리한다.")
	void scopes_idempotency_to_member() {
		// given
		Reservation first = service.create(command("same", request(1, 3, 11)));
		// when
		Reservation second = service.create(new ReservationCreateCommand("member_2", "same", request(1, 3, 12)));
		// then
		assertThat(second.getId()).isNotEqualTo(first.getId());
	}

	@Test
	@DisplayName("만료 Worker가 실행되지 않아도 만료된 구간을 다른 예약이 사용할 수 있다.")
	void reuses_expired_hold_without_worker() {
		// given
		Reservation expired = service.create(command("expired", request(1, 3, 11)));
		expire(expired);
		// when
		Reservation next = service.create(command("next", request(2, 4, 11)));
		Reservation replay = service.create(command("expired", request(1, 3, 11)));
		// then
		assertThat(replay.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
		assertThat(replay.getId()).isEqualTo(expired.getId());
		assertThat(redis.opsForHash().get(keys.key("occupancy"), "11:0")).isEqualTo(expired.getId());
		assertThat(redis.opsForHash().get(keys.key("occupancy"), "11:1")).isEqualTo(next.getId());
	}

	@ParameterizedTest
	@ValueSource(strings = {"CONFIRMING", "CONFIRMED"})
	@DisplayName("결제 진행 또는 예매 확정 상태는 원래 기한이 지나도 점유를 유지한다.")
	void blocks_payment_states_after_original_expiry(String status) {
		// given
		Reservation first = service.create(command("first", request(1, 3, 11)));
		expire(first);
		update(first.getId(), "status", status);
		// when, then
		assertError(command("next", request(1, 3, 11)), status.equals("CONFIRMED")
			? BookingError.SEAT_CONFLICT_WITH_SOLD : BookingError.SEAT_CONFLICT_WITH_HOLD);
	}

	@ParameterizedTest
	@ValueSource(strings = {"LOADING", "RECOVERING", "CLOSED"})
	@DisplayName("판매 준비가 끝나지 않은 운행은 예약을 생성하지 않는다.")
	void rejects_unready_schedule(String status) {
		// given
		redis.opsForHash().put(keys.key("meta"), "status", status);
		// when, then
		assertError(command("first", request(1, 3, 11)), BookingError.RESERVATION_REFERENCE_NOT_READY);
		assertThat(redis.hasKey(keys.key("reservations"))).isFalse();
	}

	@Test
	@DisplayName("확정 점유의 적재 완료가 확인되지 않으면 예약을 차단한다.")
	void requires_inventory_ready() {
		// given
		redis.opsForHash().delete(keys.key("meta"), "inventoryReady");
		// when, then
		assertError(command("first", request(1, 3, 11)), BookingError.RESERVATION_REFERENCE_NOT_READY);
	}

	@Test
	@DisplayName("승차역 출발이 가까우면 예약 기한을 해당 출발 시각까지만 부여한다.")
	void caps_expiry_at_boarding_departure() {
		// given
		long boardingAt = System.currentTimeMillis() + 120000;
		redis.opsForHash().put(keys.key("stops"), "1",
			mapper.writeValueAsString(Map.of("id", "101", "ordinal", 0, "departureAt", boardingAt)));
		// when
		Reservation reservation = service.create(command("first", request(1, 3, 11)));
		// then
		assertThat(reservation.getExpiresAt()).isEqualTo(boardingAt);
	}

	@Test
	@DisplayName("승차역 출발 시각이 지나면 예약을 생성하지 않는다.")
	void rejects_departed_schedule() {
		// given
		redis.opsForHash().put(keys.key("stops"), "1",
			mapper.writeValueAsString(Map.of("id", "101", "ordinal", 0, "departureAt", 1)));
		// when, then
		assertError(command("first", request(1, 3, 11)), BookingError.RESERVATION_SALES_CLOSED);
	}

	@Test
	@DisplayName("중복 좌석과 역방향 구간은 저장 전에 거부한다.")
	void rejects_invalid_seats_and_interval() {
		// given
		var duplicate = command("duplicate", request(1, 3, 11, 11));
		var reversed = command("reversed", request(3, 1, 11));
		// when, then
		assertError(duplicate, BookingError.INVALID_RESERVATION_REQUEST);
		assertError(reversed, BookingError.INVALID_RESERVATION_REQUEST);
		assertThat(redis.hasKey(keys.key("reservations"))).isFalse();
	}

	@Test
	@DisplayName("좌석 한도를 초과하거나 멱등성 키가 잘못되면 요청을 거부한다.")
	void rejects_limit_and_invalid_idempotency_key() {
		// given
		var tooMany = command("too_many", request(1, 3, 1, 2, 3, 4, 5, 6, 7, 8, 9));
		var badKey = command("has:separator", request(1, 3, 11));
		// when, then
		assertError(tooMany, BookingError.INVALID_RESERVATION_REQUEST);
		assertError(badKey, BookingError.INVALID_RESERVATION_REQUEST);
	}

	@Test
	@DisplayName("서로 다른 객차 등급의 좌석을 한 예약에 섞으면 거부한다.")
	void rejects_mixed_car_types() {
		// given
		var input = command("first", request(1, 3, 11, 14));
		// when, then
		assertError(input, BookingError.INVALID_CAR_TYPE);
	}

	@Test
	@DisplayName("점유 소유자의 예약 본문이 없으면 빈 좌석으로 취급하지 않는다.")
	void fails_closed_for_orphan_occupancy() {
		// given
		redis.opsForHash().put(keys.key("occupancy"), "11:0", "missing");
		// when, then
		assertError(command("first", request(1, 3, 11)), BookingError.RESERVATION_STATE_INCONSISTENT);
		assertThat(redis.hasKey(keys.key("reservations"))).isFalse();
	}

	@Test
	@DisplayName("키 타입이 잘못되면 어떤 예약 상태도 쓰기 전에 거부한다.")
	void detects_wrong_type_before_writes() {
		// given
		redis.opsForValue().set(keys.key("deadlines"), "bad_type");
		// when, then
		assertError(command("first", request(1, 3, 11)), BookingError.RESERVATION_STATE_INCONSISTENT);
		assertThat(redis.hasKey(keys.key("occupancy"))).isFalse();
		assertThat(redis.hasKey(keys.key("idempotency"))).isFalse();
	}

	@Test
	@DisplayName("멱등성 기록이 있어도 활성 점유가 누락되면 성공 결과를 재생하지 않는다.")
	void detects_partial_state_on_replay() {
		// given
		service.create(command("same", request(1, 3, 11)));
		redis.opsForHash().delete(keys.key("occupancy"), "11:1");
		// when, then
		assertError(command("same", request(1, 3, 11)), BookingError.RESERVATION_STATE_INCONSISTENT);
	}

	@Test
	@DisplayName("완료되지 않은 멱등성 기록은 새로운 예약으로 재실행하지 않는다.")
	void detects_incomplete_write_marker() {
		// given
		service.create(command("same", request(1, 3, 11)));
		ObjectNode entry = (ObjectNode)mapper.readTree((String)redis.opsForHash().get(keys.key("idempotency"), "member_1:same"));
		entry.put("completed", false);
		redis.opsForHash().put(keys.key("idempotency"), "member_1:same", mapper.writeValueAsString(entry));
		// when, then
		assertError(command("same", request(1, 3, 11)), BookingError.RESERVATION_STATE_INCONSISTENT);
	}

	@Test
	@DisplayName("운임 조회 후 버전이 바뀌면 이전 가격으로 예약을 저장하지 않는다.")
	void rejects_stale_fare_version() {
		// given
		var input = scriptInput();
		assertThat(repository.execute(input).code()).isEqualTo("QUOTE");
		redis.opsForHash().put(keys.key("meta"), "version", "v2");
		var price = new PriceSnapshot("v1", "g1", List.of(
			new PriceSnapshot.PricedSeat("11", PassengerType.ADULT, CarType.STANDARD, "10000")), "10000");
		// when
		var result = repository.execute(input.withPrice(price));
		// then
		assertThat(result.code()).isEqualTo("REFERENCE_CHANGED");
		assertThat(redis.hasKey(keys.key("reservations"))).isFalse();
	}

	@Test
	@DisplayName("운임 계산 중 기준정보가 한 번 변경되면 새 가격으로 재조회하여 생성한다.")
	void retries_with_fresh_reference_snapshot() {
		// given
		var changed = new java.util.concurrent.atomic.AtomicBoolean();
		doAnswer(invocation -> {
			var result = (com.sudo.raillo.booking.infrastructure.ReservationScriptResult)invocation.callRealMethod();
			if (result.code().equals("QUOTE") && changed.compareAndSet(false, true)) {
				redis.opsForHash().put(keys.key("fares"), "1:3:STANDARD", "20000");
				redis.opsForHash().put(keys.key("meta"), "version", "v2");
			}
			return result;
		}).when(repository).execute(any());
		// when
		Reservation reservation = service.create(command("changed", request(1, 3, 11)));
		// then
		assertThat(reservation.getFareVersion()).isEqualTo("v2");
		assertThat(reservation.getTotalFare()).isEqualByComparingTo("20000");
		assertThat(redis.opsForHash().size(keys.key("reservations"))).isEqualTo(1);
	}

	@Test
	@DisplayName("기준정보가 계속 바뀌면 세 번 시도한 뒤 예약을 생성하지 않고 반환한다.")
	void bounds_reference_retries() {
		// given
		var version = new java.util.concurrent.atomic.AtomicInteger(1);
		doAnswer(invocation -> {
			var result = (com.sudo.raillo.booking.infrastructure.ReservationScriptResult)invocation.callRealMethod();
			if (result.code().equals("QUOTE")) {
				redis.opsForHash().put(keys.key("meta"), "version", "v" + version.incrementAndGet());
			}
			return result;
		}).when(repository).execute(any());
		// when, then
		assertError(command("changed", request(1, 3, 11)), BookingError.RESERVATION_REFERENCE_CHANGED);
		verify(repository, times(6)).execute(any());
		assertThat(redis.hasKey(keys.key("reservations"))).isFalse();
	}

	@Test
	@DisplayName("같은 구간으로 동시에 요청하면 정확히 한 예약만 성공한다.")
	void admits_one_concurrent_owner() throws Exception {
		// given
		CyclicBarrier barrier = new CyclicBarrier(20);
		List<Callable<String>> calls = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			String key = "concurrent_" + i;
			calls.add(() -> {
				barrier.await();
				try {
					return service.create(command(key, request(1, 3, 11))).getId();
				} catch (BusinessException exception) {
					assertThat(exception.getErrorCode()).isEqualTo(BookingError.SEAT_CONFLICT_WITH_HOLD);
					return "conflict";
				}
			});
		}
		// when
		List<String> results = new ArrayList<>();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (var future : executor.invokeAll(calls)) results.add(future.get());
		}
		// then
		assertThat(results.stream().filter(value -> !value.equals("conflict"))).hasSize(1);
		assertThat(redis.opsForHash().size(keys.key("reservations"))).isEqualTo(1);
		assertThat(redis.opsForHash().size(keys.key("idempotency"))).isEqualTo(1);
	}

	@Test
	@DisplayName("동일한 요청을 동시에 재시도하면 모두 같은 예약을 반환한다.")
	void concurrent_identical_requests_replay_one_reservation() throws Exception {
		// given
		CyclicBarrier barrier = new CyclicBarrier(10);
		List<Callable<String>> calls = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			calls.add(() -> {
				barrier.await();
				return service.create(command("same", request(1, 3, 11))).getId();
			});
		}
		// when
		List<String> ids = new ArrayList<>();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (var future : executor.invokeAll(calls)) ids.add(future.get());
		}
		// then
		assertThat(ids.stream().distinct()).hasSize(1);
		assertThat(redis.opsForHash().size(keys.key("reservations"))).isEqualTo(1);
	}

	private ReservationCreateCommand command(String key, ReservationCreateRequest request) {
		return new ReservationCreateCommand("member_1", key, request);
	}

	private ReservationCreateRequest request(long departure, long arrival, long... seatIds) {
		List<SeatRequest> seats = java.util.Arrays.stream(seatIds)
			.mapToObj(id -> new SeatRequest(id, PassengerType.ADULT)).toList();
		return new ReservationCreateRequest(1785L, departure, arrival, seats);
	}

	private void assertError(ReservationCreateCommand command, BookingError error) {
		assertThatThrownBy(() -> service.create(command)).isInstanceOf(BusinessException.class)
			.hasMessage(error.getMessage()).extracting("errorCode").isEqualTo(error);
	}

	private void expire(Reservation reservation) {
		ObjectNode body = stored(reservation.getId());
		long now = System.currentTimeMillis();
		body.put("createdAt", now - 10000);
		body.put("expiresAt", now - 1);
		redis.opsForHash().put(keys.key("reservations"), reservation.getId(), mapper.writeValueAsString(body));
		redis.opsForZSet().add(keys.key("deadlines"), reservation.getId(), now - 1);
	}

	private void update(String id, String field, String value) {
		ObjectNode body = stored(id);
		body.put(field, value);
		redis.opsForHash().put(keys.key("reservations"), id, mapper.writeValueAsString(body));
	}

	private ObjectNode stored(String id) {
		return (ObjectNode)mapper.readTree((String)redis.opsForHash().get(keys.key("reservations"), id));
	}

	private ReservationScriptCommand scriptInput() {
		return new ReservationScriptCommand("1785", "member_1", "script", "a".repeat(64), "res_script", "1", "3",
			List.of(new ReservationScriptCommand.RequestedSeat("11", PassengerType.ADULT)), 600000, 0, null);
	}

	private HttpResponse<String> post(String body, String key, boolean authenticated) throws Exception {
		var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/reservations"))
			.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
		if (key != null) builder.header("Idempotency-Key", key);
		if (authenticated) {
			builder.header("Authorization", "Basic " + Base64.getEncoder()
				.encodeToString("member_1:test-password".getBytes(StandardCharsets.UTF_8)));
		}
		try (var client = HttpClient.newHttpClient()) {
			return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		}
	}
}
