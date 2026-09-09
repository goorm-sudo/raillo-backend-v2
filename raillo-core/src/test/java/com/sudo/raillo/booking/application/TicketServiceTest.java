package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.booking.application.dto.response.ReceiptResponse;
import com.sudo.raillo.booking.application.service.TicketService;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.TicketRepository;
import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentMethod;
import com.sudo.raillo.payment.adapter.persistence.PaymentJpaRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.PaymentFixture;
import com.sudo.raillo.support.helper.BookingResult;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
@Slf4j
class TicketServiceTest {

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private TicketRepository ticketRepository;

	@Autowired
	private PaymentJpaRepository paymentRepository;

	@Autowired
	private TicketService ticketService;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	private Member member;
	private Train train;
	private TrainScheduleResult trainScheduleResult;

	@BeforeEach
	void setup() {
		member = memberRepository.save(MemberFixture.create());
		train = trainTestHelper.createKTX();
		trainScheduleResult = trainScheduleTestHelper.createDefault(train);
	}

	@Test
	@DisplayName("승차권 ID로 승차권 삭제에 성공한다")
	void ticketId_deleteTicket_success() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		BookingResult bookingResult = bookingTestHelper.builder(member, trainScheduleResult)
			.addSeat(seats.get(0), PassengerType.CHILD)
			.addSeat(seats.get(1), PassengerType.VETERAN)
			.build();

		Ticket ticketToDelete = bookingResult.tickets().get(0);

		// when
		ticketService.deleteTicketById(ticketToDelete.getId());

		// then
		List<Ticket> remaining = ticketRepository.findAll();
		assertThat(remaining).hasSize(1);
	}

	@Test
	@DisplayName("예매 ID로 승차권 삭제에 성공한다")
	void bookingId_deleteTicket_success() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 2);
		BookingResult bookingResult = bookingTestHelper.builder(member, trainScheduleResult)
			.addSeat(seats.get(0), PassengerType.CHILD)
			.addSeat(seats.get(1), PassengerType.VETERAN)
			.build();

		// when
		ticketService.deleteTicketByBookingId(bookingResult.booking().getId());

		// then
		List<Ticket> remaining = ticketRepository.findAll();
		assertThat(remaining).hasSize(0);
	}

	@Test
	@DisplayName("영수증 조회에 성공한다")
	void getReceipt_success() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
		BookingResult bookingResult = bookingTestHelper.builder(member, trainScheduleResult)
			.addSeat(seats.get(0), PassengerType.ADULT)
			.build();

		Payment payment = PaymentFixture.create(member, bookingResult.booking().getOrder());
		payment.updatePaymentKey("toss-payment-key");
		payment.approve(PaymentMethod.CREDIT_CARD);
		paymentRepository.save(payment);

		Ticket ticket = bookingResult.tickets().get(0);

		// when
		ReceiptResponse response = ticketService.getReceipt(member.getMemberDetail().getMemberNo(), ticket.getId());

		// then
		assertThat(response).isNotNull();
		assertThat(response.ticketNumber()).isEqualTo(ticket.getTicketNumber());
		assertThat(response.trainNumber()).isEqualTo(String.format("%03d", train.getTrainNumber()));
		assertThat(response.carNumber()).isEqualTo(seats.get(0).getTrainCar().getCarNumber());
		assertThat(response.carType()).isEqualTo(CarType.STANDARD);
		assertThat(response.seatNumber()).isEqualTo(seats.get(0).getSeatRow() + seats.get(0).getSeatColumn());
		assertThat(response.operationDate()).isEqualTo(trainScheduleResult.trainSchedule().getOperationDate());
		assertThat(response.paymentMethod()).isEqualTo(PaymentMethod.CREDIT_CARD);
		assertThat(response.paymentKey()).isEqualTo("toss-payment-key");
		assertThat(response.amount()).isEqualByComparingTo(BigDecimal.valueOf(50000));
		assertThat(response.passengerType()).isEqualTo(PassengerType.ADULT);
	}

	@Test
	@DisplayName("존재하지 않는 회원으로 영수증 조회 시 실패한다")
	void getReceipt_memberNotFound_fail() {
		// given
		String nonExistingMemberNo = "9999999999";
		Long ticketId = 1L;

		// when & then
		assertThatThrownBy(() -> ticketService.getReceipt(nonExistingMemberNo, ticketId))
			.isInstanceOf(BusinessException.class)
			.hasMessage(MemberError.USER_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("존재하지 않는 승차권으로 영수증 조회 시 실패한다")
	void getReceipt_ticketNotFound_fail() {
		// given
		Long nonExistingTicketId = 999999L;

		// when & then
		assertThatThrownBy(() -> ticketService.getReceipt(member.getMemberDetail().getMemberNo(), nonExistingTicketId))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.TICKET_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("다른 회원의 영수증 조회 시 실패한다")
	void getReceipt_accessDenied_fail() {
		// given
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);
		BookingResult bookingResult = bookingTestHelper.builder(member, trainScheduleResult)
			.addSeat(seats.get(0), PassengerType.ADULT)
			.build();
		Ticket ticket = bookingResult.tickets().get(0);

		Member otherMember = memberRepository.save(MemberFixture.createOther());

		// when & then
		assertThatThrownBy(() -> ticketService.getReceipt(otherMember.getMemberDetail().getMemberNo(), ticket.getId()))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.TICKET_ACCESS_DENIED.getMessage());
	}
}
