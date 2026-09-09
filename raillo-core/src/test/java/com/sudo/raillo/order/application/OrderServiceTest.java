package com.sudo.raillo.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.SeatReservation;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.OrderBooking;
import com.sudo.raillo.order.domain.OrderSeatBooking;
import com.sudo.raillo.order.domain.status.OrderStatus;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.order.infrastructure.OrderBookingRepository;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.order.infrastructure.OrderSeatBookingRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.OrderFixture;
import com.sudo.raillo.support.fixture.PendingBookingFixture;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
class OrderServiceTest {

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private OrderBookingRepository orderBookingRepository;

	@Autowired
	private OrderSeatBookingRepository orderSeatBookingRepository;

	@Autowired
	private OrderService orderService;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Test
	@DisplayName("주문 코드로 주문 조회에 성공한다")
	void getOrderByCreateOrderCode_success() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Order order = OrderFixture.create(member);
		Order savedOrder = orderRepository.save(order);

		// when
		Order foundOrder = orderService.getOrderByOrderCode(savedOrder.getOrderCode());

		// then
		assertThat(foundOrder.getId()).isEqualTo(savedOrder.getId());
		assertThat(foundOrder.getOrderCode()).isEqualTo(savedOrder.getOrderCode());
	}

	@Test
	@DisplayName("존재하지 않는 주문 코드로 조회 시 예외가 발생한다")
	void getOrderByOrderCode_fail_when_createOrder_not_found() {
		// given
		String wrongOrderCode = "WRONG_ORDER_CODE";

		// when & then
		assertThatThrownBy(() -> orderService.getOrderByOrderCode(wrongOrderCode))
			.isInstanceOf(BusinessException.class)
			.hasMessage(OrderError.ORDER_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("주문 소유자가 일치하지 않으면 예외가 발생한다")
	void validateCreateOrderOwner_fail_when_owner_mismatch() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Member otherMember = memberRepository.save(MemberFixture.createOther());

		Order order = OrderFixture.create(member);
		orderRepository.save(order);

		// when & then
		assertThatThrownBy(() -> orderService.validateOrderOwner(order, otherMember))
			.isInstanceOf(BusinessException.class)
			.hasMessage(OrderError.ORDER_ACCESS_DENIED.getMessage());
	}

	@Test
	@DisplayName("주문 소유자가 일치하면 예외가 발생하지 않는다")
	void validateCreateOrderOwner_success() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Order order = OrderFixture.create(member);
		orderRepository.save(order);

		// when & then
		assertThatNoException()
			.isThrownBy(() -> orderService.validateOrderOwner(order, member));
	}

	@Test
	@DisplayName("주문 생성에 성공한다")
	void createOrder_success() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult result = trainScheduleTestHelper.createDefault(train);
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		Reservation reservation = PendingBookingFixture.builder()
			.withMemberNo(member.getMemberDetail().getMemberNo())
			.withTrainScheduleId(result.trainSchedule().getId())
			.withDepartureStopId(result.scheduleStops().get(0).getId())
			.withArrivalStopId(result.scheduleStops().get(1).getId())
			.withSeatReservations(List.of(new SeatReservation(seats.get(0).getId(), PassengerType.ADULT)))
			.build();

		// when
		orderService.createOrder(member.getMemberDetail().getMemberNo(), List.of(reservation));

		// then
		List<Order> orders = orderRepository.findAll();
		assertThat(orders).hasSize(1);

		// Order 검증
		Order savedOrder = orders.get(0);
		assertThat(savedOrder.getMember().getId()).isEqualTo(member.getId());
		assertThat(savedOrder.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		// 운임 계산: 성인(50000×1.0) = 50,000원
		assertThat(savedOrder.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(50000));

		// OrderBooking 검증
		List<OrderBooking> orderBookings = orderBookingRepository.findAll();
		assertThat(orderBookings).hasSize(1);

		OrderBooking savedOrderBooking = orderBookings.get(0);
		assertThat(savedOrderBooking.getPendingBookingId()).isEqualTo(reservation.getId());
		assertThat(savedOrderBooking.getOrder().getId()).isEqualTo(savedOrder.getId());
		assertThat(savedOrderBooking.getTrainSchedule().getId()).isEqualTo(result.trainSchedule().getId());
		assertThat(savedOrderBooking.getDepartureStop().getId()).isEqualTo(result.scheduleStops().get(0).getId());
		assertThat(savedOrderBooking.getArrivalStop().getId()).isEqualTo(result.scheduleStops().get(1).getId());

		// OrderSeatBooking 검증
		List<OrderSeatBooking> orderSeatBookings = orderSeatBookingRepository.findAll();
		assertThat(orderSeatBookings).hasSize(1);
		assertThat(orderSeatBookings.get(0).getSeatId()).isEqualTo(seats.get(0).getId());
		assertThat(orderSeatBookings.get(0).getPassengerType()).isEqualTo(PassengerType.ADULT);
	}

	@Test
	@DisplayName("여러 개의 PendingBooking으로 주문 생성에 성공한다")
	void createOrder_success_with_multiple_pending_bookings() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult result = trainScheduleTestHelper.createDefault(train);
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);

		Reservation reservation1 = PendingBookingFixture.builder()
			.withMemberNo(member.getMemberDetail().getMemberNo())
			.withTrainScheduleId(result.trainSchedule().getId())
			.withDepartureStopId(result.scheduleStops().get(0).getId())
			.withArrivalStopId(result.scheduleStops().get(1).getId())
			.withSeatReservations(List.of(new SeatReservation(seats.get(0).getId(), PassengerType.ADULT)))
			.build();

		Reservation reservation2 = PendingBookingFixture.builder()
			.withMemberNo(member.getMemberDetail().getMemberNo())
			.withTrainScheduleId(result.trainSchedule().getId())
			.withDepartureStopId(result.scheduleStops().get(0).getId())
			.withArrivalStopId(result.scheduleStops().get(1).getId())
			.withSeatReservations(List.of(new SeatReservation(seats.get(1).getId(), PassengerType.SENIOR)))
			.build();

		List<Reservation> reservations = List.of(reservation1, reservation2);

		// when
		orderService.createOrder(member.getMemberDetail().getMemberNo(), reservations);

		// then
		List<Order> orders = orderRepository.findAll();
		assertThat(orders).hasSize(1);

		Order savedOrder = orders.get(0);
		// 운임 계산: 성인(50000×1.0) + 경로(50000×0.7) = 85,000원
		assertThat(savedOrder.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(85000));

		List<OrderBooking> orderBookings = orderBookingRepository.findAll();
		assertThat(orderBookings).hasSize(2);

		List<OrderSeatBooking> orderSeatBookings = orderSeatBookingRepository.findAll();
		assertThat(orderSeatBookings).hasSize(2);
		assertThat(orderSeatBookings)
			.extracting(OrderSeatBooking::getPassengerType)
			.containsExactlyInAnyOrder(PassengerType.ADULT, PassengerType.SENIOR);
	}

	@Test
	@DisplayName("존재하지 않는 회원 번호로 주문 생성 시 예외가 발생한다")
	void createOrder_fail_when_member_not_found() {
		// given
		String nonExistentMemberNo = "999999999999";
		Member member = memberRepository.save(MemberFixture.create());

		Reservation reservation = PendingBookingFixture.builder()
			.withMemberNo(member.getMemberDetail().getMemberNo())
			.withTrainScheduleId(1L)
			.withDepartureStopId(1L)
			.withArrivalStopId(2L)
			.withSeatReservations(List.of(new SeatReservation(1L, PassengerType.ADULT)))
			.withTotalFare(BigDecimal.valueOf(30000))
			.build();

		// when & then
		assertThatThrownBy(() -> orderService.createOrder(nonExistentMemberNo, List.of(reservation)))
			.isInstanceOf(BusinessException.class)
			.hasMessage(MemberError.USER_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("빈 예약 리스트로 주문 생성 시 예외가 발생한다")
	void createOrder_fail_when_pending_bookings_empty() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		String memberNo = member.getMemberDetail().getMemberNo();

		List<Reservation> emptyReservations = Collections.emptyList();

		// when & then
		assertThatThrownBy(() -> orderService.createOrder(memberNo, emptyReservations))
			.isInstanceOf(BusinessException.class)
			.hasMessage(OrderError.EMPTY_PENDING_BOOKINGS.getMessage());
	}

	@Test
	@DisplayName("예약 리스트가 null이면 주문 생성 시 예외가 발생한다")
	void createOrder_fail_when_pending_bookings_null() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		String memberNo = member.getMemberDetail().getMemberNo();

		List<Reservation> emptyReservations = null;

		// when & then
		assertThatThrownBy(() -> orderService.createOrder(memberNo, emptyReservations))
			.isInstanceOf(BusinessException.class)
			.hasMessage(OrderError.EMPTY_PENDING_BOOKINGS.getMessage());
	}
}
