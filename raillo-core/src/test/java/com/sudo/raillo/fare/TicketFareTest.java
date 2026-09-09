package com.sudo.raillo.fare;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.TicketRepository;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.OrderResult;
import com.sudo.raillo.support.helper.OrderTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.StationFare;
import com.sudo.raillo.train.domain.Train;
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
class TicketFareTest {

	@Autowired
	private BookingService bookingService;

	@Autowired
	private TicketRepository ticketRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private StationFareRepository stationFareRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private OrderTestHelper orderTestHelper;

	private Member member;
	private Train train;
	private TrainScheduleResult trainScheduleResult;
	private ScheduleStop departureStop;
	private ScheduleStop arrivalStop;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		train = trainTestHelper.createCustomKTX(3, 2);
		trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		departureStop = trainScheduleResult.scheduleStops().get(0);
		arrivalStop = trainScheduleResult.scheduleStops().get(1);
	}

	@Test
	@DisplayName("일반석 성인 1명 승차권 금액이 정상적으로 저장된다")
	void create_ticket_standard_adult() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);

		OrderResult orderResult = orderTestHelper.builder(member)
			.addOrderBooking(trainScheduleResult)
				.addSeat(seats.get(0), PassengerType.ADULT)
				.and()
			.build();

		Order order = orderResult.order();
		order.completePayment();

		// when
		bookingService.createBookingFromOrder(order);

		// then
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal expectedFare = stationFare.getStandardFare();

		List<Ticket> tickets = ticketRepository.findAll();
		assertThat(tickets).hasSize(1);
		assertThat(tickets.get(0).getFare()).isEqualByComparingTo(expectedFare);
	}

	@Test
	@DisplayName("일반석 어린이 1명 승차권 금액이 정상적으로 저장된다")
	void create_ticket_standard_child() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);

		OrderResult orderResult = orderTestHelper.builder(member)
			.addOrderBooking(trainScheduleResult)
				.addSeat(seats.get(0), PassengerType.CHILD)
				.and()
			.build();

		Order order = orderResult.order();
		order.completePayment();

		// when
		bookingService.createBookingFromOrder(order);

		// then
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal expectedFare = stationFare.getStandardFare().multiply(BigDecimal.valueOf(0.6));

		List<Ticket> tickets = ticketRepository.findAll();
		assertThat(tickets).hasSize(1);
		assertThat(tickets.get(0).getFare()).isEqualByComparingTo(expectedFare);
	}

	@Test
	@DisplayName("특실 성인 1명 승차권 금액이 정상적으로 저장된다")
	void create_ticket_firstClass_adult() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.FIRST_CLASS, 1);

		OrderResult orderResult = orderTestHelper.builder(member)
			.addOrderBooking(trainScheduleResult)
				.addSeat(seats.get(0), PassengerType.ADULT)
				.and()
			.build();

		Order order = orderResult.order();
		order.completePayment();

		// when
		bookingService.createBookingFromOrder(order);

		// then
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal expectedFare = stationFare.getFirstClassFare();

		List<Ticket> tickets = ticketRepository.findAll();
		assertThat(tickets).hasSize(1);
		assertThat(tickets.get(0).getFare()).isEqualByComparingTo(expectedFare);
	}

	@Test
	@DisplayName("일반석 성인 2명 승차권 금액이 정상적으로 저장된다")
	void create_ticket_standard_multipleAdults() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		OrderResult orderResult = orderTestHelper.builder(member)
			.addOrderBooking(trainScheduleResult)
				.addSeat(seats.get(0), PassengerType.ADULT)
				.addSeat(seats.get(1), PassengerType.ADULT)
				.and()
			.build();

		Order order = orderResult.order();
		order.completePayment();

		// when
		bookingService.createBookingFromOrder(order);

		// then
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal expectedFare = stationFare.getStandardFare();

		List<Ticket> tickets = ticketRepository.findAll();
		assertThat(tickets).hasSize(2);
		assertThat(tickets).allSatisfy(ticket ->
			assertThat(ticket.getFare()).isEqualByComparingTo(expectedFare));
	}

	@Test
	@DisplayName("일반석 성인 1명 + 어린이 1명 승차권 금액이 정상적으로 저장된다")
	void create_ticket_standard_adultAndChild() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		OrderResult orderResult = orderTestHelper.builder(member)
			.addOrderBooking(trainScheduleResult)
				.addSeat(seats.get(0), PassengerType.ADULT)
				.addSeat(seats.get(1), PassengerType.CHILD)
				.and()
			.build();

		Order order = orderResult.order();
		order.completePayment();

		// when
		bookingService.createBookingFromOrder(order);

		// then
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal adultFare = stationFare.getStandardFare();
		BigDecimal childFare = adultFare.multiply(BigDecimal.valueOf(0.6));

		List<Ticket> tickets = ticketRepository.findAll();
		assertThat(tickets).hasSize(2);
		assertThat(tickets.get(0).getFare()).isEqualByComparingTo(adultFare);
		assertThat(tickets.get(1).getFare()).isEqualByComparingTo(childFare);
	}

	@Test
	@DisplayName("모든 승차권 금액의 합계가 주문 총금액과 일치한다")
	void create_ticket_total_matches_order() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		OrderResult orderResult = orderTestHelper.builder(member)
			.addOrderBooking(trainScheduleResult)
				.addSeat(seats.get(0), PassengerType.ADULT)
				.addSeat(seats.get(1), PassengerType.CHILD)
				.and()
			.build();

		Order order = orderResult.order();

		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal adultFare = stationFare.getStandardFare();
		BigDecimal childFare = adultFare.multiply(BigDecimal.valueOf(0.6));
		BigDecimal expectedTotal = adultFare.add(childFare);

		order.completePayment();

		// when
		bookingService.createBookingFromOrder(order);

		// then
		List<Ticket> tickets = ticketRepository.findAll();
		BigDecimal ticketFareSum = tickets.stream()
			.map(Ticket::getFare)
			.reduce(BigDecimal.ZERO, BigDecimal::add);

		assertThat(ticketFareSum).isEqualByComparingTo(order.getTotalAmount());
		assertThat(ticketFareSum).isEqualByComparingTo(expectedTotal);
	}

	@Test
	@DisplayName("여러 예매로 생성된 승차권 금액이 정상적으로 저장된다")
	void create_ticket_multipleOrderBookings() {
		// given
		List<Seat> standardSeats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
		List<Seat> firstClassSeats = trainTestHelper.getSeats(train, CarType.FIRST_CLASS, 1);

		OrderResult orderResult = orderTestHelper.builder(member)
			.addOrderBooking(trainScheduleResult)
				.addSeat(standardSeats.get(0), PassengerType.ADULT)
				.and()
			.addOrderBooking(trainScheduleResult)
				.addSeat(firstClassSeats.get(0), PassengerType.ADULT)
				.and()
			.build();

		Order order = orderResult.order();
		order.completePayment();

		// when
		bookingService.createBookingFromOrder(order);

		// then
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal standardFare = stationFare.getStandardFare();
		BigDecimal firstClassFare = stationFare.getFirstClassFare();

		List<Ticket> tickets = ticketRepository.findAll();
		assertThat(tickets).hasSize(2);
		assertThat(tickets.get(0).getFare()).isEqualByComparingTo(standardFare);
		assertThat(tickets.get(1).getFare()).isEqualByComparingTo(firstClassFare);
	}

	@Test
	@DisplayName("서로 다른 스케줄의 여러 예매와 다양한 승객 유형의 승차권 금액이 정상적으로 저장된다")
	void create_ticket_differentSchedules_multipleSeats() {
		// given
		// 1. 첫 번째 스케줄: 서울 -> 부산 (일반석 2좌석)
		List<Seat> schedule1Seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		// 2. 두 번째 스케줄: 서울 -> 대전 (특실 3좌석, 다른 열차)
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
		ScheduleStop schedule2Departure = schedule2Result.scheduleStops().get(0);
		ScheduleStop schedule2Arrival = schedule2Result.scheduleStops().get(1);

		// 3. Order 생성
		OrderResult orderResult = orderTestHelper.builder(member)
			.addOrderBooking(trainScheduleResult)
				.addSeat(schedule1Seats.get(0), PassengerType.ADULT)
				.addSeat(schedule1Seats.get(1), PassengerType.CHILD)
				.and()
			.addOrderBooking(schedule2Result)
				.addSeat(schedule2Seats.get(0), PassengerType.ADULT)
				.addSeat(schedule2Seats.get(1), PassengerType.SENIOR)
				.addSeat(schedule2Seats.get(2), PassengerType.CHILD)
				.and()
			.build();

		Order order = orderResult.order();
		order.completePayment();

		// when
		bookingService.createBookingFromOrder(order);

		// then
		// 요금 계산: 스케줄 1 (서울->부산, 일반석)
		StationFare stationFare1 = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal fare1Adult = stationFare1.getStandardFare();
		BigDecimal fare1Child = fare1Adult.multiply(BigDecimal.valueOf(0.6));

		// 요금 계산: 스케줄 2 (서울->대전, 특실)
		StationFare stationFare2 = getStationFare(schedule2Departure.getStation().getId(), schedule2Arrival.getStation().getId());
		BigDecimal fare2Adult = stationFare2.getFirstClassFare();
		BigDecimal fare2Senior = fare2Adult.multiply(BigDecimal.valueOf(0.7));
		BigDecimal fare2Child = fare2Adult.multiply(BigDecimal.valueOf(0.6));

		List<Ticket> tickets = ticketRepository.findAll();
		assertThat(tickets).hasSize(5);

		// 스케줄 1 승차권 검증
		assertThat(tickets.get(0).getFare()).isEqualByComparingTo(fare1Adult);
		assertThat(tickets.get(1).getFare()).isEqualByComparingTo(fare1Child);

		// 스케줄 2 승차권 검증
		assertThat(tickets.get(2).getFare()).isEqualByComparingTo(fare2Adult);
		assertThat(tickets.get(3).getFare()).isEqualByComparingTo(fare2Senior);
		assertThat(tickets.get(4).getFare()).isEqualByComparingTo(fare2Child);
	}

	private StationFare getStationFare(Long departureStationId, Long arrivalStationId) {
		return stationFareRepository.
			findByDepartureStationIdAndArrivalStationId(departureStationId, arrivalStationId).orElseThrow();
	}
}
