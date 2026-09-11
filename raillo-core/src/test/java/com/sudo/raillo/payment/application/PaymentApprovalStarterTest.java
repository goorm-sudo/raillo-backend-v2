package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.payment.domain.exception.PaymentError;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.OrderFixture;

@ServiceTest
class PaymentApprovalStarterTest {

	@Autowired private PaymentApprovalStarter paymentApprovalStarter;
	@Autowired private PaymentAttemptRepository paymentAttemptRepository;
	@Autowired private PaymentRepository paymentRepository;
	@Autowired private MemberRepository memberRepository;
	@Autowired private OrderRepository orderRepository;
	@Autowired private JdbcTemplate jdbcTemplate;

	private Payment payment;

	@BeforeEach
	void setUp() {
		var member = memberRepository.save(MemberFixture.create());
		var order = orderRepository.save(OrderFixture.create(member));
		payment = paymentRepository.save(Payment.create(member, order));
	}

	@Test
	@DisplayName("startApprovalInNewTransaction으로 IN_PROGRESS attempt를 저장하고 paymentKey도 반영한다")
	void startApprovalInNewTransaction_persists() {
		// when
		Long attemptDbId = paymentApprovalStarter
			.startApprovalInNewTransaction(payment.getId(), "attempt-abc", "toss-key")
			.attemptDbId();

		// then
		PaymentAttempt saved = paymentAttemptRepository.findById(attemptDbId).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(PaymentAttemptStatus.IN_PROGRESS);
		assertThat(saved.getAttemptId()).isEqualTo("attempt-abc");
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getPaymentKey()).isEqualTo("toss-key");
	}

	@Test
	@DisplayName("같은 결제에 서로 다른 attemptId로 동시에 요청하면 승인 시도 하나만 생성된다")
	void concurrent_attempts_create_only_one_approval() throws Exception {
		// given
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> startTogether("first", ready, start));
			var second = executor.submit(() -> startTogether("second", ready, start));
			try {
				assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			} finally {
				// when
				start.countDown();
			}
			var outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

			// then
			assertThat(outcomes)
				.containsExactlyInAnyOrder("started", PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS.name());
			assertThat(jdbcTemplate.queryForObject(
				"select count(*) from payment_attempt where payment_id = ?",
				Long.class, payment.getId())).isEqualTo(1);
			String storedKey = jdbcTemplate.queryForObject(
				"select payment_key from payment_attempt where payment_id = ?",
				String.class, payment.getId());
			assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getPaymentKey())
				.isEqualTo(storedKey);
		}
	}

	@Test
	@DisplayName("진행 중인 승인에 다른 attemptId로 재요청하면 기존 paymentKey를 변경하지 않는다")
	void rejects_new_attempt_without_overwriting_payment_key() {
		// given
		paymentApprovalStarter.startApprovalInNewTransaction(payment.getId(), "first", "first-key");

		// when / then
		assertThatThrownBy(() -> paymentApprovalStarter
			.startApprovalInNewTransaction(payment.getId(), "second", "second-key"))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_ATTEMPT_IN_PROGRESS);
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getPaymentKey()).isEqualTo("first-key");
		assertThat(paymentAttemptRepository.findByAttemptId("second")).isEmpty();
	}

	@Test
	@DisplayName("attempt 실패만 먼저 커밋된 경우에도 다른 승인 시도를 시작하지 않는다")
	void rejects_new_attempt_during_failure_transition() {
		// given: Payment.fail 커밋 전의 짧은 간격을 재현한다
		PaymentAttempt failed = PaymentAttempt.startApproval(payment.getId(), "first", "first-key");
		failed.markFailed("REJECT", "카드 거절");
		paymentAttemptRepository.save(failed);
		payment.updatePaymentKey("first-key");
		paymentRepository.save(payment);

		// when / then
		assertThatThrownBy(() -> paymentApprovalStarter
			.startApprovalInNewTransaction(payment.getId(), "second", "second-key"))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_ATTEMPT_ALREADY_FAILED);
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getPaymentKey()).isEqualTo("first-key");
	}

	@Test
	@DisplayName("같은 승인 시도를 다시 시작하면 기존 ID를 반환하고 새 실행 권한을 주지 않는다")
	void existing_attempt_does_not_grant_execution_again() {
		// given
		PaymentAttemptStartResult first = paymentApprovalStarter
			.startApprovalInNewTransaction(payment.getId(), "first", "first-key");

		// when
		PaymentAttemptStartResult second = paymentApprovalStarter
			.startApprovalInNewTransaction(payment.getId(), "first", "first-key");

		// then
		assertThat(first.created()).isTrue();
		assertThat(second.created()).isFalse();
		assertThat(second.attemptDbId()).isEqualTo(first.attemptDbId());
	}

	@Test
	@DisplayName("같은 attemptId의 paymentKey를 변경하면 잠금 획득 후에도 요청을 거절한다")
	void existing_attempt_rejects_changed_key_under_lock() {
		// given
		paymentApprovalStarter.startApprovalInNewTransaction(payment.getId(), "first", "first-key");

		// when / then
		assertThatThrownBy(() -> paymentApprovalStarter
			.startApprovalInNewTransaction(payment.getId(), "first", "changed-key"))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_ATTEMPT_REQUEST_MISMATCH);
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getPaymentKey()).isEqualTo("first-key");
	}

	@Test
	@DisplayName("paymentKey 저장이 실패하면 같은 트랜잭션의 attempt도 롤백된다")
	void key_conflict_rolls_back_attempt() {
		// given: 다른 결제가 이미 사용 중인 paymentKey
		var otherMember = memberRepository.save(MemberFixture.createOther());
		var otherOrder = orderRepository.save(OrderFixture.create(otherMember));
		Payment other = Payment.create(otherMember, otherOrder);
		other.updatePaymentKey("duplicate-key");
		paymentRepository.save(other);

		// when / then
		assertThatThrownBy(() -> paymentApprovalStarter
			.startApprovalInNewTransaction(payment.getId(), "new", "duplicate-key"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(paymentAttemptRepository.findByAttemptId("new")).isEmpty();
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getPaymentKey()).isNull();
	}

	private String startTogether(String attemptId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new AssertionError("동시 요청 시작 대기 시간 초과");
		}
		try {
			paymentApprovalStarter.startApprovalInNewTransaction(payment.getId(), attemptId, attemptId + "-key");
			return "started";
		} catch (BusinessException e) {
			return ((PaymentError) e.getErrorCode()).name();
		}
	}
}
