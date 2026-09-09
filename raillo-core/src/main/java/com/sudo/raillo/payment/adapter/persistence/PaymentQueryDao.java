package com.sudo.raillo.payment.adapter.persistence;

import static com.sudo.raillo.booking.domain.QBooking.*;
import static com.sudo.raillo.member.domain.QMember.*;
import static com.sudo.raillo.payment.domain.QPayment.*;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sudo.raillo.payment.adapter.persistence.PaymentProjection;
import com.sudo.raillo.payment.adapter.persistence.QPaymentProjection;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PaymentQueryDao {

	private final JPAQueryFactory queryFactory;

	public List<PaymentProjection> findPaymentHistoryByMemberId(Long memberId) {
		return queryFactory
			.select(new QPaymentProjection(
				payment.id,
				payment.paymentKey,
				booking.bookingCode,
				payment.amount,
				payment.paymentMethod,
				payment.paymentStatus,
				payment.paidAt,
				payment.cancelledAt,
				payment.refundedAt))
			.from(payment)
			.join(payment.member, member)
			.where(payment.member.id.eq(memberId))
			.orderBy(payment.paidAt.desc()).fetch();
	}
}
