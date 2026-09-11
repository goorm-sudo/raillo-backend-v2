package com.sudo.raillo.payment.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.Comment;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.sudo.raillo.common.exception.DomainException;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Table(name = "payment_attempt", indexes = {
	@Index(name = "idx_payment_attempt_payment_id", columnList = "payment_id"),
	@Index(name = "idx_payment_attempt_status_type", columnList = "status,attempt_type"),
	@Index(name = "uk_payment_attempt_attempt_id", columnList = "attempt_id", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
public class PaymentAttempt {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "payment_attempt_id")
	private Long id;

	@Column(name = "payment_id", nullable = false)
	@Comment("payment.payment_id를 참조 (FK 제약은 걸지 않음)")
	private Long paymentId;

	@Column(name = "attempt_id", nullable = false, length = 64)
	@Comment("외부 idempotency key. DB PK와 별개로 두는 이유는 도메인 문서 참고: docs/payment-consistency.md#paymentattempt-idempotency-key")
	private String attemptId;

	@Enumerated(EnumType.STRING)
	@Column(name = "attempt_type", nullable = false, length = 20)
	private PaymentAttemptType attemptType;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PaymentAttemptStatus status;

	@Column(name = "payment_key")
	private String paymentKey;

	@Column(name = "error_code", length = 100)
	private String errorCode;

	@Column(name = "error_message", length = 500)
	private String errorMessage;

	@Column(name = "processing_owner", length = 100)
	@Comment("Recovery Worker 동시 처리 방지용 소유자 식별자")
	private String processingOwner;

	@Column(name = "processing_lease_until")
	private LocalDateTime processingLeaseUntil;

	@Column(name = "next_retry_at")
	private LocalDateTime nextRetryAt;

	@CreatedDate
	@Column(name = "created_at", updatable = false, nullable = false)
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	public static PaymentAttempt startApproval(Long paymentId, String attemptId, String paymentKey) {
		PaymentAttempt attempt = new PaymentAttempt();
		attempt.paymentId = paymentId;
		attempt.attemptId = attemptId;
		attempt.paymentKey = paymentKey;
		attempt.attemptType = PaymentAttemptType.APPROVAL;
		attempt.status = PaymentAttemptStatus.IN_PROGRESS;
		return attempt;
	}

	public void markSucceeded() {
		if (this.status != PaymentAttemptStatus.IN_PROGRESS) {
			throw new DomainException(PaymentError.PAYMENT_ATTEMPT_NOT_TRANSITIONABLE);
		}
		this.status = PaymentAttemptStatus.SUCCEEDED;
	}

	public void markFailed(String errorCode, String errorMessage) {
		if (this.status != PaymentAttemptStatus.IN_PROGRESS) {
			throw new DomainException(PaymentError.PAYMENT_ATTEMPT_NOT_TRANSITIONABLE);
		}
		this.status = PaymentAttemptStatus.FAILED;
		this.errorCode = errorCode;
		this.errorMessage = errorMessage;
	}
}
