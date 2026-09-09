package com.sudo.raillo.payment.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.Comment;

import com.sudo.raillo.common.exception.DomainException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Table(name = "payment", indexes = {
	@Index(name = "idx_payment_order_id", columnList = "order_id")
})
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "payment_id")
	@Comment("결제 ID")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = true, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	@Comment("멤버 ID")
	private Member member;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_id", nullable = true, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	@Comment("주문 ID")
	private Order order;

	@Column(name = "order_code", nullable = false, updatable = false)
	@Comment("주문번호 (토스 결제 요청 시 사용, TempBooking의 bookingCode)")
	private String orderCode;

	@Column(name = "payment_key", unique = true)
	@Comment("토스페이먼츠 결제 고유 키 (결제 승인 후 발급)")
	private String paymentKey;

	@Column(name = "amount", nullable = false)
	@Comment("결제 금액")
	private BigDecimal amount;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_method")
	private PaymentMethod paymentMethod;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_status", nullable = false)
	@Comment("결제 상태")
	private PaymentStatus paymentStatus;

	@Column(name = "paid_at")
	@Comment("결제 일자")
	private LocalDateTime paidAt;

	@Column(name = "failed_at")
	@Comment("결제 실패 일시")
	private LocalDateTime failedAt;

	@Column(name = "cancelled_at")
	@Comment("결제 취소 일자")
	private LocalDateTime cancelledAt;

	@Column(name = "refunded_at")
	@Comment("환불 처리 일자")
	private LocalDateTime refundedAt;

	@Column(name = "failure_code")
	@Comment("PG 실패 코드, ex) REJECT_CARD_PAYMENT")
	private String failureCode;

	@Column(name = "failure_reason")
	@Comment("결제 실패 사유")
	private String failureMessage;

	public static Payment create(Member member, Order order) {
		Payment payment = new Payment();
		payment.member = member;
		payment.order = order;
		payment.orderCode = order.getOrderCode();
		payment.amount = order.getTotalAmount();
		payment.paymentStatus = PaymentStatus.PENDING;
		return payment;
	}

	// paymentKey 업데이트
	public void updatePaymentKey(String paymentKey) {
		this.paymentKey = paymentKey;
	}

	public void approve(PaymentMethod paymentMethod) {
		validatePayable();
		this.paymentMethod = paymentMethod;
		this.paymentStatus = PaymentStatus.PAID;
		this.paidAt = LocalDateTime.now();
	}

	public void cancel(String reason) {
		validateCancellable();
		this.paymentStatus = PaymentStatus.CANCELLED;
		this.cancelledAt = LocalDateTime.now();
	}

	public void refund() {
		validateRefundable();
		this.paymentStatus = PaymentStatus.REFUNDED;
		this.refundedAt = LocalDateTime.now();
	}

	/**
	 * 결제 실패 처리
	 * @param failureCode 토스 PG사에서 받은 에러 코드 (ex: NOT_FOUND_PAYMENT, REJECT_CARD_PAYMENT)
	 * @param failureMessage 토스 PG사에서 받은 에러 메시지
	 */
	public void fail(String failureCode, String failureMessage) {
		validateFailable();
		this.paymentStatus = PaymentStatus.FAILED;
		this.failureCode = failureCode;
		this.failureMessage = failureMessage;
		this.failedAt = LocalDateTime.now();
	}

	private void validatePayable() {
		if (this.paymentStatus != PaymentStatus.PENDING) {
			throw new DomainException(PaymentError.PAYMENT_NOT_APPROVABLE);
		}
	}

	private void validateCancellable() {
		if (this.paymentStatus != PaymentStatus.PENDING) {
			throw new DomainException(PaymentError.PAYMENT_NOT_CANCELLABLE);
		}
	}

	private void validateRefundable() {
		if (this.paidAt == null || this.paymentStatus != PaymentStatus.PAID) {
			throw new DomainException(PaymentError.PAYMENT_NOT_REFUNDABLE);
		}
	}

	private void validateFailable() {
		if (this.paymentStatus != PaymentStatus.PENDING) {
			throw new DomainException(PaymentError.PAYMENT_CANNOT_FAIL);
		}
	}
}
