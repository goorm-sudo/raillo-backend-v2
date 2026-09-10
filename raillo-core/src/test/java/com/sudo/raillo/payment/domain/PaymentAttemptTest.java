package com.sudo.raillo.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sudo.raillo.common.exception.DomainException;

class PaymentAttemptTest {

    @Test
    @DisplayName("승인 attempt를 IN_PROGRESS 상태로 생성한다")
    void startApproval_isInProgress() {
        PaymentAttempt attempt = PaymentAttempt.startApproval(1L, "attempt-abc", "toss-key");

        assertThat(attempt.getPaymentId()).isEqualTo(1L);
        assertThat(attempt.getAttemptId()).isEqualTo("attempt-abc");
        assertThat(attempt.getPaymentKey()).isEqualTo("toss-key");
        assertThat(attempt.getAttemptType()).isEqualTo(PaymentAttemptType.APPROVAL);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("IN_PROGRESS attempt를 SUCCEEDED로 전환한다")
    void markSucceeded_fromInProgress() {
        PaymentAttempt attempt = PaymentAttempt.startApproval(1L, "attempt-abc", "toss-key");

        attempt.markSucceeded();

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("IN_PROGRESS attempt를 FAILED로 전환하고 에러 정보를 기록한다")
    void markFailed_fromInProgress() {
        PaymentAttempt attempt = PaymentAttempt.startApproval(1L, "attempt-abc", "toss-key");

        attempt.markFailed("REJECT_CARD_PAYMENT", "카드 승인 거절");

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(attempt.getErrorCode()).isEqualTo("REJECT_CARD_PAYMENT");
        assertThat(attempt.getErrorMessage()).isEqualTo("카드 승인 거절");
    }

    @Test
    @DisplayName("SUCCEEDED 상태에서 다시 markSucceeded는 도메인 예외")
    void markSucceeded_fromSucceeded_throws() {
        PaymentAttempt attempt = PaymentAttempt.startApproval(1L, "attempt-abc", "toss-key");
        attempt.markSucceeded();

        assertThatThrownBy(attempt::markSucceeded).isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("SUCCEEDED 상태에서 markFailed는 도메인 예외")
    void markFailed_fromSucceeded_throws() {
        PaymentAttempt attempt = PaymentAttempt.startApproval(1L, "attempt-abc", "toss-key");
        attempt.markSucceeded();

        assertThatThrownBy(() -> attempt.markFailed("X", "Y")).isInstanceOf(DomainException.class);
    }
}
