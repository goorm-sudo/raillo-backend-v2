package com.sudo.raillo.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sudo.raillo.common.exception.DomainException;

class PaymentOutboxTest {

	@Test
	@DisplayName("BookingConfirmed outbox row를 PENDING으로 생성한다")
	void forBookingConfirmed_isPending() {
		PaymentOutbox outbox = PaymentOutbox.forBookingConfirmed(
			100L, "payment:100:booking-confirmed", "{\"pendingBookingIds\":[\"pb-1\"]}"
		);

		assertThat(outbox.getType()).isEqualTo(PaymentOutboxType.BOOKING_CONFIRMED);
		assertThat(outbox.getAggregateId()).isEqualTo(100L);
		assertThat(outbox.getDeduplicationKey()).isEqualTo("payment:100:booking-confirmed");
		assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
		assertThat(outbox.getRetryCount()).isZero();
	}

	@Test
	@DisplayName("PENDING outbox를 DONE으로 전환한다")
	void markDone_fromPending() {
		PaymentOutbox outbox = PaymentOutbox.forBookingConfirmed(1L, "k", "{}");

		outbox.markDone();

		assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.DONE);
		assertThat(outbox.getProcessedAt()).isNotNull();
	}

	@Test
	@DisplayName("실패 시 retry_count를 증가시키고 next_retry_at을 설정한다")
	void markRetry_incrementsCount() {
		PaymentOutbox outbox = PaymentOutbox.forBookingConfirmed(1L, "k", "{}");
		LocalDateTime nextRetry = LocalDateTime.now().plusMinutes(1);

		outbox.markRetry(nextRetry);

		assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
		assertThat(outbox.getRetryCount()).isEqualTo(1);
		assertThat(outbox.getNextRetryAt()).isEqualTo(nextRetry);
	}

	@Test
	@DisplayName("최대 재시도 초과 시 FAILED로 전환한다")
	void markFailed_setsStatusAndProcessedAt() {
		PaymentOutbox outbox = PaymentOutbox.forBookingConfirmed(1L, "k", "{}");

		outbox.markFailed();

		assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.FAILED);
		assertThat(outbox.getProcessedAt()).isNotNull();
	}

	@Test
	@DisplayName("DONE 상태에서 markDone 재호출은 도메인 예외")
	void markDone_fromDone_throws() {
		PaymentOutbox outbox = PaymentOutbox.forBookingConfirmed(1L, "k", "{}");
		outbox.markDone();

		assertThatThrownBy(outbox::markDone).isInstanceOf(DomainException.class);
	}
}
