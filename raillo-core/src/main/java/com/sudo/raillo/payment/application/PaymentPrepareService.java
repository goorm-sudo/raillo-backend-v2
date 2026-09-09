package com.sudo.raillo.payment.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.provided.PaymentPreparer;
import com.sudo.raillo.payment.application.required.MemberFinder;
import com.sudo.raillo.payment.application.required.OrderRegister;
import com.sudo.raillo.payment.application.required.PendingBookingReader;
import com.sudo.raillo.payment.application.required.SeatConflictValidator;
import com.sudo.raillo.payment.domain.Payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 준비 유스케이스.
 *
 * <p>1. PendingBooking 조회 및 소유자 검증
 * <p>2. 좌석 충돌 검증
 * <p>3. Order 생성 (PENDING)
 * <p>4. Payment 생성 (PENDING)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentPrepareService implements PaymentPreparer {

	private final PaymentModifier paymentModifier;
	private final OrderRegister orderRegister;
	private final MemberFinder memberFinder;
	private final PendingBookingReader pendingBookingReader;
	private final SeatConflictValidator seatConflictValidator;

	@Override
	public PaymentPrepareResult prepare(PaymentPrepareCommand command, String memberNo) {
		List<PendingBooking> pendingBookings = pendingBookingReader.getPendingBookings(command.pendingBookingIds(), memberNo);
		seatConflictValidator.validateSeatConflicts(pendingBookings);

		Member member = memberFinder.getMemberByMemberNo(memberNo);
		Order order = orderRegister.createOrder(memberNo, pendingBookings);
		Payment payment = paymentModifier.createPayment(member, order);

		log.info("[결제 준비 완료] orderId={}, paymentId={}, amount={}, pendingBookingCount={}",
			order.getOrderCode(), payment.getId(), order.getTotalAmount(), pendingBookings.size());

		return PaymentPrepareResult.from(order);
	}
}
