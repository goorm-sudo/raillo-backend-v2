package com.sudo.raillo.fare;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.SeatReservation;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.application.OrderService;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.OrderBooking;
import com.sudo.raillo.order.domain.OrderSeatBooking;
import com.sudo.raillo.order.infrastructure.OrderBookingRepository;
import com.sudo.raillo.order.infrastructure.OrderSeatBookingRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.PendingBookingFixture;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.StationFare;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.infrastructure.StationFareRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
class OrderFareTest {

	@Autowired
	private OrderService orderService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private StationFareRepository stationFareRepository;

	@Autowired
	private OrderBookingRepository orderBookingRepository;

	@Autowired
	private OrderSeatBookingRepository orderSeatBookingRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	private Member member;
	private Train train;
	private ScheduleStop departureStop;
	private ScheduleStop arrivalStop;
	private TrainSchedule trainSchedule;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		train = trainTestHelper.createCustomKTX(3, 2);
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		trainSchedule = trainScheduleResult.trainSchedule();
		departureStop = trainScheduleResult.scheduleStops().get(0);
		arrivalStop = trainScheduleResult.scheduleStops().get(1);
	}

	@Test
	@DisplayName("일반석 성인 1명 주문 금액이 정상적으로 계산된다")
	void create_order_standard_adult() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);

		Reservation reservation = PendingBookingFixture.builder()
			.withMemberNo(member.getMemberDetail().getMemberNo())
			.withTrainScheduleId(trainSchedule.getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withSeatReservations(List.of(
				new SeatReservation(seats.get(0).getId(), PassengerType.ADULT)
			))
			.build();

		// when
		Order order = orderService.createOrder(member.getMemberDetail().getMemberNo(), List.of(reservation));

		// then
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal expectedFare = stationFare.getStandardFare();

		// order 검증
		assertThat(order.getTotalAmount()).isEqualByComparingTo(expectedFare);

		// orderBooking 검증
		List<OrderBooking> orderBookings = orderBookingRepository.findByOrderId(order.getId());
		assertThat(orderBookings.get(0).getTotalFare()).isEqualByComparingTo(expectedFare);

		// orderSeatBooking 검증
		List<OrderSeatBooking> orderSeatBookings = orderSeatBookingRepository
			.findByOrderBookingId(orderBookings.get(0).getId());
		assertThat(orderSeatBookings.get(0).getFare()).isEqualByComparingTo(expectedFare);
	}

	@Test
	@DisplayName("일반석 어린이 1명 주문 금액이 정상적으로 계산된다")
	void create_order_standard_child() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);

		Reservation reservation = PendingBookingFixture.builder()
			.withMemberNo(member.getMemberDetail().getMemberNo())
			.withTrainScheduleId(trainSchedule.getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withSeatReservations(List.of(
				new SeatReservation(seats.get(0).getId(), PassengerType.CHILD)
			))
			.build();

		// when
		Order order = orderService.createOrder(member.getMemberDetail().getMemberNo(), List.of(reservation));

		// then
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal expectedFare = stationFare.getStandardFare().multiply(BigDecimal.valueOf(0.6));

		// order 검증
		assertThat(order.getTotalAmount()).isEqualByComparingTo(expectedFare);

		// orderBooking 검증
		List<OrderBooking> orderBookings = orderBookingRepository.findByOrderId(order.getId());
		assertThat(orderBookings.get(0).getTotalFare()).isEqualByComparingTo(expectedFare);

		// orderSeatBooking 검증
		List<OrderSeatBooking> orderSeatBookings = orderSeatBookingRepository
			.findByOrderBookingId(orderBookings.get(0).getId());
		assertThat(orderSeatBookings.get(0).getFare()).isEqualByComparingTo(expectedFare);
	}

	@Test
	@DisplayName("특실 성인 1명 주문 금액이 정상적으로 계산된다")
	void create_order_firstClass_adult() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.FIRST_CLASS, 1);

		Reservation reservation = PendingBookingFixture.builder()
			.withMemberNo(member.getMemberDetail().getMemberNo())
			.withTrainScheduleId(trainSchedule.getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withSeatReservations(List.of(
				new SeatReservation(seats.get(0).getId(), PassengerType.ADULT)
			))
			.build();

		// when
		Order order = orderService.createOrder(member.getMemberDetail().getMemberNo(), List.of(reservation));

		// then
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal expectedFare = stationFare.getFirstClassFare();

		// order 검증
		assertThat(order.getTotalAmount()).isEqualByComparingTo(expectedFare);

		// orderBooking 검증
		List<OrderBooking> orderBookings = orderBookingRepository.findByOrderId(order.getId());
		assertThat(orderBookings.get(0).getTotalFare()).isEqualByComparingTo(expectedFare);

		// orderSeatBooking 검증
		List<OrderSeatBooking> orderSeatBookings = orderSeatBookingRepository
			.findByOrderBookingId(orderBookings.get(0).getId());
		assertThat(orderSeatBookings.get(0).getFare()).isEqualByComparingTo(expectedFare);
	}

	@Test
	@DisplayName("일반석 성인 2명 주문 금액이 정상적으로 합산된다")
	void create_order_standard_multipleAdults() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		Reservation reservation = PendingBookingFixture.builder()
			.withMemberNo(member.getMemberDetail().getMemberNo())
			.withTrainScheduleId(trainSchedule.getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withSeatReservations(List.of(
				new SeatReservation(seats.get(0).getId(), PassengerType.ADULT),
				new SeatReservation(seats.get(1).getId(), PassengerType.ADULT)
			))
			.build();

		// when
		Order order = orderService.createOrder(member.getMemberDetail().getMemberNo(), List.of(reservation));

		// then
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal unitFare = stationFare.getStandardFare();
		BigDecimal expectedFare = unitFare.multiply(BigDecimal.valueOf(2));

		// order 검증
		assertThat(order.getTotalAmount()).isEqualByComparingTo(expectedFare);

		// orderBooking 검증
		List<OrderBooking> orderBookings = orderBookingRepository.findByOrderId(order.getId());
		assertThat(orderBookings.get(0).getTotalFare()).isEqualByComparingTo(expectedFare);

		// orderSeatBooking 검증
		List<OrderSeatBooking> orderSeatBookings = orderSeatBookingRepository
			.findByOrderBookingId(orderBookings.get(0).getId());
		assertThat(orderSeatBookings).allSatisfy(sb -> assertThat(sb.getFare()).isEqualByComparingTo(unitFare));
	}

	@Test
	@DisplayName("일반석 성인 1명 + 어린이 1명 주문 금액이 정상적으로 계산된다")
	void create_order_standard_adultAndChild() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		Reservation reservation = PendingBookingFixture.builder()
			.withMemberNo(member.getMemberDetail().getMemberNo())
			.withTrainScheduleId(trainSchedule.getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withSeatReservations(List.of(
				new SeatReservation(seats.get(0).getId(), PassengerType.ADULT),
				new SeatReservation(seats.get(1).getId(), PassengerType.CHILD)
			))
			.build();

		// when
		Order order = orderService.createOrder(member.getMemberDetail().getMemberNo(), List.of(reservation));

		// then
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal adultFare = stationFare.getStandardFare();
		BigDecimal childFare = adultFare.multiply(BigDecimal.valueOf(0.6));
		BigDecimal expectedFare = adultFare.add(childFare);

		// order 검증
		assertThat(order.getTotalAmount()).isEqualByComparingTo(expectedFare);

		// orderBooking 검증
		List<OrderBooking> orderBookings = orderBookingRepository.findByOrderId(order.getId());
		assertThat(orderBookings.get(0).getTotalFare()).isEqualByComparingTo(expectedFare);

		// orderSeatBooking 검증
		List<OrderSeatBooking> orderSeatBookings = orderSeatBookingRepository
			.findByOrderBookingId(orderBookings.get(0).getId());
		assertThat(orderSeatBookings).hasSize(2);

		OrderSeatBooking adultSeatBooking = orderSeatBookings.get(0);
		assertThat(adultSeatBooking.getFare()).isEqualByComparingTo(adultFare);

		OrderSeatBooking childSeatBooking = orderSeatBookings.get(1);
		assertThat(childSeatBooking.getFare()).isEqualByComparingTo(childFare);
	}

	@Test
	@DisplayName("여러 예약으로 생성된 주문 금액이 정상적으로 합산된다")
	void create_order_multiplePendingBookings() {
		// given
		List<Seat> standardSeats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
		List<Seat> firstClassSeats = trainTestHelper.getSeats(train, CarType.FIRST_CLASS, 1);

		Reservation reservation1 = PendingBookingFixture.builder()
			.withMemberNo(member.getMemberDetail().getMemberNo())
			.withTrainScheduleId(trainSchedule.getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withSeatReservations(List.of(
				new SeatReservation(standardSeats.get(0).getId(), PassengerType.ADULT)
			))
			.build();

		Reservation reservation2 = PendingBookingFixture.builder()
			.withMemberNo(member.getMemberDetail().getMemberNo())
			.withTrainScheduleId(trainSchedule.getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withSeatReservations(List.of(
				new SeatReservation(firstClassSeats.get(0).getId(), PassengerType.ADULT)
			))
			.build();

		// when
		Order order = orderService.createOrder(
			member.getMemberDetail().getMemberNo(),
			List.of(reservation1, reservation2)
		);

		// then
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal standardFare = stationFare.getStandardFare();
		BigDecimal firstClassFare = stationFare.getFirstClassFare();
		BigDecimal expectedFare = standardFare.add(firstClassFare);

		// order 검증
		assertThat(order.getTotalAmount()).isEqualByComparingTo(expectedFare);

		// orderBooking 검증
		List<OrderBooking> orderBookings = orderBookingRepository.findByOrderId(order.getId());
		assertThat(orderBookings).hasSize(2);

		OrderBooking standardOrderBooking = orderBookings.get(0);
		assertThat(standardOrderBooking.getTotalFare()).isEqualByComparingTo(standardFare);

		OrderBooking firstClassOrderBooking = orderBookings.get(1);
		assertThat(firstClassOrderBooking.getTotalFare()).isEqualByComparingTo(firstClassFare);

		// orderSeatBooking 검증
		List<OrderSeatBooking> standardOrderSeatBookings = orderSeatBookingRepository
			.findByOrderBookingId(standardOrderBooking.getId());
		assertThat(standardOrderSeatBookings.get(0).getFare())
			.isEqualByComparingTo(standardFare);

		List<OrderSeatBooking> firstClassOrderSeatBookings = orderSeatBookingRepository
			.findByOrderBookingId(firstClassOrderBooking.getId());
		assertThat(firstClassOrderSeatBookings.get(0).getFare())
			.isEqualByComparingTo(firstClassFare);
	}

	@Test
	@DisplayName("서로 다른 스케줄의 여러 예약과 다양한 승객 유형으로 생성된 주문 금액이 정상적으로 합산된다")
	void create_order_differentSchedules_multipleSeats() {
		// given
		// 1. 첫 번째 스케줄 준비: 서울 -> 부산 (일반석 2좌석)
		List<Seat> schedule1Seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		Long s1SeatId1 = schedule1Seats.get(0).getId(); // 성인용
		Long s1SeatId2 = schedule1Seats.get(1).getId(); // 어린이용

		// 2. 두 번째 스케줄 준비: 서울 -> 대전 (특실 3좌석, 다른 열차)
		trainScheduleTestHelper.createOrUpdateStationFare("서울", "대전", 25000, 50000);
		Train train2 = trainTestHelper.createCustomKTX(3, 2);
		TrainScheduleResult schedule2Result = trainScheduleTestHelper.builder()
			.scheduleName("KTX 002 경부선")
			.operationDate(LocalDate.now())
			.train(train2)
			.addStop("서울", null, LocalTime.of(9, 0))
			.addStop("대전", LocalTime.of(10, 30), null)
			.build();

		List<Seat> schedule2Seats = trainTestHelper.getSeats(train2, CarType.FIRST_CLASS, 3);
		Long s2SeatId1 = schedule2Seats.get(0).getId(); // 성인용
		Long s2SeatId2 = schedule2Seats.get(1).getId(); // 경로용
		Long s2SeatId3 = schedule2Seats.get(2).getId(); // 어린이용

		ScheduleStop schedule2Departure = schedule2Result.scheduleStops().get(0);
		ScheduleStop schedule2Arrival = schedule2Result.scheduleStops().get(1);

		// 3. PendingBooking 생성
		Reservation reservation1 = PendingBookingFixture.builder()
			.withMemberNo(member.getMemberDetail().getMemberNo())
			.withTrainScheduleId(trainSchedule.getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withSeatReservations(List.of(
				new SeatReservation(s1SeatId1, PassengerType.ADULT),
				new SeatReservation(s1SeatId2, PassengerType.CHILD)
			))
			.build();

		Reservation reservation2 = PendingBookingFixture.builder()
			.withMemberNo(member.getMemberDetail().getMemberNo())
			.withTrainScheduleId(schedule2Result.trainSchedule().getId())
			.withDepartureStopId(schedule2Departure.getId())
			.withArrivalStopId(schedule2Arrival.getId())
			.withSeatReservations(List.of(
				new SeatReservation(s2SeatId1, PassengerType.ADULT),
				new SeatReservation(s2SeatId2, PassengerType.SENIOR),
				new SeatReservation(s2SeatId3, PassengerType.CHILD)
			))
			.build();

		// when
		Order order = orderService.createOrder(
			member.getMemberDetail().getMemberNo(),
			List.of(reservation1, reservation2)
		);

		// then
		// 요금 계산: 스케줄 1 (서울->부산, 일반석)
		StationFare stationFare1 = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal fare1Adult = stationFare1.getStandardFare();
		BigDecimal fare1Child = fare1Adult.multiply(BigDecimal.valueOf(0.6));
		BigDecimal booking1TotalFare = fare1Adult.add(fare1Child);

		// 요금 계산: 스케줄 2 (서울->대전, 특실)
		StationFare stationFare2 = getStationFare(schedule2Departure.getStation().getId(), schedule2Arrival.getStation().getId());
		BigDecimal fare2Adult = stationFare2.getFirstClassFare();
		BigDecimal fare2Senior = fare2Adult.multiply(BigDecimal.valueOf(0.7));
		BigDecimal fare2Child = fare2Adult.multiply(BigDecimal.valueOf(0.6));
		BigDecimal booking2TotalFare = fare2Adult.add(fare2Senior).add(fare2Child);

		BigDecimal expectedTotalFare = booking1TotalFare.add(booking2TotalFare);

		// order 검증
		assertThat(order.getTotalAmount()).isEqualByComparingTo(expectedTotalFare);

		// orderBooking 검증
		List<OrderBooking> orderBookings = orderBookingRepository.findByOrderId(order.getId());
		assertThat(orderBookings).hasSize(2);

		OrderBooking orderBooking1 = orderBookings.get(0);
		assertThat(orderBooking1.getTotalFare()).isEqualByComparingTo(booking1TotalFare);

		OrderBooking orderBooking2 = orderBookings.get(1);
		assertThat(orderBooking2.getTotalFare()).isEqualByComparingTo(booking2TotalFare);

		// orderSeatBooking 검증
		List<OrderSeatBooking> seatBookings1 = orderSeatBookingRepository.findByOrderBookingId(orderBooking1.getId());
		assertThat(seatBookings1).hasSize(2);
		assertThat(seatBookings1.get(0).getFare()).isEqualByComparingTo(fare1Adult);
		assertThat(seatBookings1.get(1).getFare()).isEqualByComparingTo(fare1Child);

		List<OrderSeatBooking> seatBookings2 = orderSeatBookingRepository.findByOrderBookingId(orderBooking2.getId());
		assertThat(seatBookings2).hasSize(3);
		assertThat(seatBookings2.get(0).getFare()).isEqualByComparingTo(fare2Adult);
		assertThat(seatBookings2.get(1).getFare()).isEqualByComparingTo(fare2Senior);
		assertThat(seatBookings2.get(2).getFare()).isEqualByComparingTo(fare2Child);
	}

	private StationFare getStationFare(Long departureStopId, Long arrivalStopId) {
		return stationFareRepository.
			findByDepartureStationIdAndArrivalStationId(departureStopId, arrivalStopId).orElseThrow();
	}
}
