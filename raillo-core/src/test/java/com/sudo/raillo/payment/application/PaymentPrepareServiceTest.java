package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;
import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.status.OrderStatus;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.application.PaymentPrepareCommand;
import com.sudo.raillo.payment.application.PaymentPrepareResult;
import com.sudo.raillo.payment.application.provided.PaymentPreparer;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.PendingBookingFixture;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

@ServiceTest
class PaymentPrepareServiceTest {

	@Autowired
	private PaymentPreparer paymentPreparer;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private BookingRedisRepository bookingRedisRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	private Member member;
	private TrainScheduleResult trainScheduleResult;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());

		Train train = trainTestHelper.createKTX();
		trainScheduleResult = trainScheduleTestHelper.createDefault(train);
	}

	@Test
	@DisplayName("결제 준비 시 Payment가 정상적으로 생성된다")
	void preparePayment_success() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();

		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);

		List<Long> seatIds = trainTestHelper.getSeatIds(
			trainScheduleResult.trainSchedule().getTrain(),
			CarType.STANDARD,
			1
		);

		List<PendingSeatBooking> pendingSeatBookings = List.of(
			new PendingSeatBooking(seatIds.get(0), PassengerType.ADULT)
		);

		PendingBooking pendingBooking = PendingBookingFixture.builder()
			.withMemberNo(memberNo)
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withPendingSeatBookings(pendingSeatBookings)
			.withTotalFare(BigDecimal.valueOf(50000))
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking);

		PaymentPrepareCommand request = new PaymentPrepareCommand(List.of(pendingBooking.getId()));

		// when
		PaymentPrepareResult result = paymentPreparer.prepare(request, memberNo);

		// then
		Order saved = orderRepository.findByOrderCode(result.orderCode()).orElseThrow();
		assertThat(saved.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(result.totalAmount()).isEqualByComparingTo(pendingBooking.getTotalFare());
	}

	@Test
	@DisplayName("여러 좌석이 포함된 여러 PendingBooking으로 결제 준비 시 금액이 합산된다")
	void preparePayment_multiplePendingBookingsWithMultipleSeats_success() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();

		// 좌석이 더 많은 열차와 스케줄 생성
		Train train = trainTestHelper.createSmallTestTrain();
		TrainScheduleResult scheduleResult = trainScheduleTestHelper.createDefault(train);

		ScheduleStop departureStop = scheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = scheduleResult.scheduleStops().get(1);

		List<Long> seatIds = trainTestHelper.getSeatIds(train, CarType.STANDARD, 4);

		// 첫 번째 PendingBooking: 2명 (성인 + 어린이)
		PendingBooking pendingBooking1 = PendingBookingFixture.builder()
			.withMemberNo(memberNo)
			.withTrainScheduleId(scheduleResult.trainSchedule().getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withPendingSeatBookings(List.of(
				new PendingSeatBooking(seatIds.get(0), PassengerType.ADULT),
				new PendingSeatBooking(seatIds.get(1), PassengerType.CHILD)
			))
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking1);

		// 두 번째 PendingBooking: 2명 (성인 + 경로)
		PendingBooking pendingBooking2 = PendingBookingFixture.builder()
			.withMemberNo(memberNo)
			.withTrainScheduleId(scheduleResult.trainSchedule().getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withPendingSeatBookings(List.of(
				new PendingSeatBooking(seatIds.get(2), PassengerType.ADULT),
				new PendingSeatBooking(seatIds.get(3), PassengerType.SENIOR)
			))
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking2);

		PaymentPrepareCommand request = new PaymentPrepareCommand(
			List.of(pendingBooking1.getId(), pendingBooking2.getId())
		);

		// when
		PaymentPrepareResult result = paymentPreparer.prepare(request, memberNo);

		// then
		// 실제 운임 계산: 성인(50000×1.0) + 어린이(50000×0.6) + 성인(50000×1.0) + 경로(50000×0.7) = 165,000원
		BigDecimal expectedAmount = BigDecimal.valueOf(50000 + 30000 + 50000 + 35000);
		Order saved = orderRepository.findByOrderCode(result.orderCode()).orElseThrow();
		assertThat(saved.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(result.totalAmount()).isEqualByComparingTo(expectedAmount);
	}

	@Test
	@DisplayName("존재하지 않는 PendingBooking ID로 결제 준비 시 예외가 발생한다")
	void preparePayment_pendingBookingNotFound_throwsException() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();
		String nonExistentId = UUID.randomUUID().toString();

		PaymentPrepareCommand request = new PaymentPrepareCommand(List.of(nonExistentId));

		// when & then
		assertThatThrownBy(() -> paymentPreparer.prepare(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.PENDING_BOOKING_EXPIRED)
			.hasMessage(BookingError.PENDING_BOOKING_EXPIRED.getMessage());
	}

	@Test
	@DisplayName("다른 사용자의 PendingBooking으로 결제 준비 시 예외가 발생한다")
	void preparePayment_accessDenied_throwsException() {
		// given
		Member otherMember = memberRepository.save(MemberFixture.createOther());
		String otherMemberNo = otherMember.getMemberDetail().getMemberNo();

		String currentMemberNo = member.getMemberDetail().getMemberNo();

		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);

		List<Long> seatIds = trainTestHelper.getSeatIds(
			trainScheduleResult.trainSchedule().getTrain(),
			CarType.STANDARD,
			1
		);

		// 다른 사용자의 PendingBooking 생성
		PendingBooking othersPendingBooking = PendingBookingFixture.builder()
			.withMemberNo(otherMemberNo)
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withPendingSeatBookings(List.of(
				new PendingSeatBooking(seatIds.get(0), PassengerType.ADULT)
			))
			.withTotalFare(BigDecimal.valueOf(50000))
			.build();
		bookingRedisRepository.savePendingBooking(othersPendingBooking);

		PaymentPrepareCommand request = new PaymentPrepareCommand(List.of(othersPendingBooking.getId()));

		// when & then (현재 사용자가 다른 사용자의 PendingBooking으로 결제 시도)
		assertThatThrownBy(() -> paymentPreparer.prepare(request, currentMemberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.PENDING_BOOKING_ACCESS_DENIED)
			.hasMessage(BookingError.PENDING_BOOKING_ACCESS_DENIED.getMessage());
	}

	@Test
	@DisplayName("회원 탈퇴 후 결제 시도 시 USER_NOT_FOUND 예외가 발생한다")
	void preparePayment_memberDeleted_throwsException() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();

		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);

		List<Long> seatIds = trainTestHelper.getSeatIds(
			trainScheduleResult.trainSchedule().getTrain(),
			CarType.STANDARD,
			1
		);

		// 유효한 회원의 PendingBooking 생성
		PendingBooking pendingBooking = PendingBookingFixture.builder()
			.withMemberNo(memberNo)
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withPendingSeatBookings(List.of(
				new PendingSeatBooking(seatIds.get(0), PassengerType.ADULT)
			))
			.withTotalFare(BigDecimal.valueOf(50000))
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking);

		// 회원 탈퇴
		memberRepository.delete(member);

		PaymentPrepareCommand request = new PaymentPrepareCommand(List.of(pendingBooking.getId()));

		// when & then (탈퇴한 회원의 토큰으로 결제 시도)
		assertThatThrownBy(() -> paymentPreparer.prepare(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", MemberError.USER_NOT_FOUND)
			.hasMessage(MemberError.USER_NOT_FOUND.getMessage());
	}
}
