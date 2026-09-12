package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentAttemptIdsTest {

	@Test
	@DisplayName("같은 paymentKey에 대한 승인 attempt_id는 항상 같은 값이다")
	void forApproval_isDeterministic() {
		String first = PaymentAttemptIds.forApproval("toss_pk_test_12345");
		String second = PaymentAttemptIds.forApproval("toss_pk_test_12345");

		assertThat(first).isEqualTo(second);
	}

	@Test
	@DisplayName("다른 paymentKey는 다른 승인 attempt_id를 반환한다")
	void forApproval_differsByPaymentKey() {
		String a = PaymentAttemptIds.forApproval("toss_pk_a");
		String b = PaymentAttemptIds.forApproval("toss_pk_b");

		assertThat(a).isNotEqualTo(b);
	}

	@Test
	@DisplayName("승인과 취소 attempt_id는 paymentKey가 같아도 다른 값을 반환한다")
	void approvalAndCancellation_neverCollide() {
		String approval = PaymentAttemptIds.forApproval("toss_pk_x");
		String cancellation = PaymentAttemptIds.forCancellation("toss_pk_x", 0);

		assertThat(approval).isNotEqualTo(cancellation);
	}

	@Test
	@DisplayName("같은 paymentKey라도 취소 sequence가 다르면 다른 attempt_id를 반환한다")
	void forCancellation_differsBySequence() {
		String first = PaymentAttemptIds.forCancellation("toss_pk_y", 1);
		String second = PaymentAttemptIds.forCancellation("toss_pk_y", 2);

		assertThat(first).isNotEqualTo(second);
	}

	@Test
	@DisplayName("파생된 attempt_id는 attempt_id 컬럼 길이(64자)에 들어간다")
	void derivedId_fitsColumnLength() {
		String approval = PaymentAttemptIds.forApproval("any-key");
		String cancellation = PaymentAttemptIds.forCancellation("any-key", 0);

		assertThat(approval).hasSize(64);
		assertThat(cancellation).hasSize(64);
	}
}
