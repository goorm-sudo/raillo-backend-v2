package com.sudo.raillo.fare;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.dto.response.PendingBookingCreateResponse;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
class ReservationFareTest {

	@Autowired
	private PendingBookingFacade pendingBookingFacade;

	@Autowired
	private BookingRedisRepository bookingRedisRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private StationFareRepository stationFareRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	private Member member;
	private TrainScheduleResult trainScheduleResult;
	private TrainSchedule trainSchedule;
	private ScheduleStop departureStop;
	private ScheduleStop arrivalStop;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createCustomKTX(3, 2);
		trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		trainSchedule = trainScheduleResult.trainSchedule();
		departureStop = trainScheduleResult.scheduleStops().get(0);
		arrivalStop = trainScheduleResult.scheduleStops().get(1);
	}

	@Test
	@DisplayName("일반석 성인 1명 예약 금액이 정상적으로 계산된다")
	void create_pendingBooking_standard_adult() {
		// given
		List<Seat> seats = trainTestHelper.getAvailableSeats(trainSchedule, CarType.STANDARD, 1);

		PendingBookingCreateRequest request = new PendingBookingCreateRequest(
			trainScheduleResult.trainSchedule().getId(),
			departureStop.getStation().getId(),
			arrivalStop.getStation().getId(),
			List.of(PassengerType.ADULT),
			seats.stream().map(Seat::getId).toList()
		);

		// when
		PendingBookingCreateResponse response = pendingBookingFacade.createPendingBooking(
			request,
			member.getMemberDetail().getMemberNo()
		);

		// then
		Reservation reservation = bookingRedisRepository
			.getPendingBooking(response.pendingBookingId()).orElseThrow();
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());

		assertThat(reservation.getTotalFare()).isEqualByComparingTo(stationFare.getStandardFare());
	}

	@Test
	@DisplayName("일반석 어린이 1명 예약 금액이 정상적으로 계산된다")
	void create_pendingBooking_standard_child() {
		// given
		List<Seat> seats = trainTestHelper.getAvailableSeats(trainSchedule, CarType.STANDARD, 1);

		PendingBookingCreateRequest request = new PendingBookingCreateRequest(
			trainScheduleResult.trainSchedule().getId(),
			departureStop.getStation().getId(),
			arrivalStop.getStation().getId(),
			List.of(PassengerType.CHILD),
			seats.stream().map(Seat::getId).toList()
		);

		// when
		PendingBookingCreateResponse response = pendingBookingFacade.createPendingBooking(
			request,
			member.getMemberDetail().getMemberNo()
		);

		// then
		Reservation reservation = bookingRedisRepository
			.getPendingBooking(response.pendingBookingId()).orElseThrow();
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());

		assertThat(reservation.getTotalFare())
			.isEqualByComparingTo(stationFare.getStandardFare().multiply(BigDecimal.valueOf(0.6)));
	}

	@Test
	@DisplayName("특실 성인 1명 예약 금액이 정상적으로 계산된다")
	void create_pendingBooking_firstClass_adult() {
		// given
		List<Seat> seats = trainTestHelper.getAvailableSeats(trainSchedule, CarType.FIRST_CLASS, 1);

		PendingBookingCreateRequest request = new PendingBookingCreateRequest(
			trainScheduleResult.trainSchedule().getId(),
			departureStop.getStation().getId(),
			arrivalStop.getStation().getId(),
			List.of(PassengerType.ADULT),
			seats.stream().map(Seat::getId).toList()
		);

		// when
		PendingBookingCreateResponse response = pendingBookingFacade.createPendingBooking(
			request,
			member.getMemberDetail().getMemberNo()
		);

		// then
		Reservation reservation = bookingRedisRepository
			.getPendingBooking(response.pendingBookingId()).orElseThrow();
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());

		assertThat(reservation.getTotalFare()).isEqualByComparingTo(stationFare.getFirstClassFare());
	}

	@Test
	@DisplayName("일반석 성인 2명 예약 금액이 정상적으로 합산된다")
	void create_pendingBooking_standard_multipleAdults() {
		// given
		List<Seat> seats = trainTestHelper.getAvailableSeats(trainSchedule, CarType.STANDARD, 2);

		PendingBookingCreateRequest request = new PendingBookingCreateRequest(
			trainScheduleResult.trainSchedule().getId(),
			departureStop.getStation().getId(),
			arrivalStop.getStation().getId(),
			List.of(PassengerType.ADULT, PassengerType.ADULT),
			seats.stream().map(Seat::getId).toList()
		);

		// when
		PendingBookingCreateResponse response = pendingBookingFacade.createPendingBooking(
			request,
			member.getMemberDetail().getMemberNo()
		);

		// then
		Reservation reservation = bookingRedisRepository
			.getPendingBooking(response.pendingBookingId()).orElseThrow();
		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());

		assertThat(reservation.getTotalFare())
			.isEqualByComparingTo(stationFare.getStandardFare().multiply(BigDecimal.valueOf(2)));
	}

	@Test
	@DisplayName("일반석 성인 1명 + 어린이 1명 예약 금액이 정상적으로 계산된다")
	void create_pendingBooking_standard_adultAndChild() {
		// given
		List<Seat> seats = trainTestHelper.getAvailableSeats(trainSchedule, CarType.STANDARD, 2);

		PendingBookingCreateRequest request = new PendingBookingCreateRequest(
			trainScheduleResult.trainSchedule().getId(),
			departureStop.getStation().getId(),
			arrivalStop.getStation().getId(),
			List.of(PassengerType.ADULT, PassengerType.CHILD),
			seats.stream().map(Seat::getId).toList()
		);

		// when
		PendingBookingCreateResponse response = pendingBookingFacade.createPendingBooking(
			request,
			member.getMemberDetail().getMemberNo()
		);

		// then
		Reservation reservation = bookingRedisRepository
			.getPendingBooking(response.pendingBookingId()).orElseThrow();

		StationFare stationFare = getStationFare(departureStop.getStation().getId(), arrivalStop.getStation().getId());
		BigDecimal adultFare = stationFare.getStandardFare();
		BigDecimal childFare = stationFare.getStandardFare().multiply(BigDecimal.valueOf(0.6));

		assertThat(reservation.getTotalFare()).isEqualByComparingTo(adultFare.add(childFare));
	}


	private StationFare getStationFare(Long departureStopId, Long arrivalStopId) {
		return stationFareRepository.
			findByDepartureStationIdAndArrivalStationId(departureStopId, arrivalStopId).orElseThrow();
	}
}
