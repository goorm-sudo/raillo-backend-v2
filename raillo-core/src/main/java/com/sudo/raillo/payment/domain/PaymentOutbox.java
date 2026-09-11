package com.sudo.raillo.payment.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.Comment;
import org.springframework.data.annotation.CreatedDate;
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
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Table(name = "payment_outbox", indexes = {
	@Index(name = "idx_payment_outbox_status_next_retry", columnList = "status,next_retry_at"),
	@Index(name = "uk_payment_outbox_dedup", columnList = "deduplication_key", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
public class PaymentOutbox {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "payment_outbox_id")
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 30)
	private PaymentOutboxType type;

	@Column(name = "aggregate_id", nullable = false)
	@Comment("payment_id 또는 order_id 등 소비자 컨텍스트")
	private Long aggregateId;

	@Column(name = "deduplication_key", nullable = false, length = 200)
	@Comment("재발행 시 소비자 측 중복 처리 방지 키")
	private String deduplicationKey;

	@Lob
	@Column(name = "payload", nullable = false, columnDefinition = "TEXT")
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PaymentOutboxStatus status;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	@Column(name = "next_retry_at")
	private LocalDateTime nextRetryAt;

	@CreatedDate
	@Column(name = "created_at", updatable = false, nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "processed_at")
	private LocalDateTime processedAt;

	public static PaymentOutbox forBookingConfirmed(Long aggregateId, String deduplicationKey, String payload) {
		PaymentOutbox outbox = new PaymentOutbox();
		outbox.type = PaymentOutboxType.BOOKING_CONFIRMED;
		outbox.aggregateId = aggregateId;
		outbox.deduplicationKey = deduplicationKey;
		outbox.payload = payload;
		outbox.status = PaymentOutboxStatus.PENDING;
		outbox.retryCount = 0;
		return outbox;
	}

	public void markDone() {
		if (this.status != PaymentOutboxStatus.PENDING) {
			throw new DomainException(PaymentError.PAYMENT_OUTBOX_NOT_TRANSITIONABLE);
		}
		this.status = PaymentOutboxStatus.DONE;
		this.processedAt = LocalDateTime.now();
	}

	public void markRetry(LocalDateTime nextRetryAt) {
		if (this.status != PaymentOutboxStatus.PENDING) {
			throw new DomainException(PaymentError.PAYMENT_OUTBOX_NOT_TRANSITIONABLE);
		}
		this.retryCount++;
		this.nextRetryAt = nextRetryAt;
	}

	public void markFailed() {
		if (this.status != PaymentOutboxStatus.PENDING) {
			throw new DomainException(PaymentError.PAYMENT_OUTBOX_NOT_TRANSITIONABLE);
		}
		this.status = PaymentOutboxStatus.FAILED;
		this.processedAt = LocalDateTime.now();
	}
}
