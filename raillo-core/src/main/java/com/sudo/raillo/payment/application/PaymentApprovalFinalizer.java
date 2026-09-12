package com.sudo.raillo.payment.application;

import com.sudo.raillo.payment.application.command.PaymentConfirmCommand;
import com.sudo.raillo.payment.application.result.PaymentConfirmResult;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.common.exception.BusinessException;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.required.BookingCreator;
import com.sudo.raillo.payment.application.required.OrderReader;
import com.sudo.raillo.payment.application.required.PaymentAttemptRepository;
import com.sudo.raillo.payment.application.required.PaymentGateway.GatewayConfirmResult;
import com.sudo.raillo.payment.application.required.PaymentOutboxRepository;
import com.sudo.raillo.payment.application.required.PaymentRepository;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentOutbox;
import com.sudo.raillo.payment.domain.exception.PaymentError;

import lombok.RequiredArgsConstructor;

/**
 * Toss 승인 성공 결과를 로컬 DB에 원자적으로 확정한다.
 *
 * <p>외부 API 호출 전에 조회한 엔티티는 사용하지 않는다. 별도 트랜잭션에서 Payment를 다시 잠그고
 * Order, PaymentAttempt를 최신 상태로 조회한 뒤 Order/Booking/Payment/Attempt/Outbox를 함께 커밋한다.
 */
@Component
@RequiredArgsConstructor
public class PaymentApprovalFinalizer {

	private final PaymentRepository paymentRepository;
	private final PaymentAttemptRepository paymentAttemptRepository;
	private final PaymentOutboxRepository paymentOutboxRepository;
	private final OrderReader orderReader;
	private final BookingCreator bookingCreator;
	private final PaymentValidator paymentValidator;
	private final ObjectMapper objectMapper;

	@Transactional
	public PaymentConfirmResult finalizeApproval(
		Long paymentId,
		Long attemptDbId,
		PaymentConfirmCommand command,
		GatewayConfirmResult gatewayResult,
		List<PendingBooking> pendingBookings
	) {
		Payment payment = paymentRepository.findByIdForUpdate(paymentId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_NOT_FOUND));
		Order order = orderReader.getOrderByOrderCode(command.orderId());
		PaymentAttempt attempt = paymentAttemptRepository.findById(attemptDbId)
			.orElseThrow(() -> new BusinessException(PaymentError.PAYMENT_ATTEMPT_NOT_FOUND));

		paymentValidator.validateApprovalAttempt(attempt, paymentId, command.paymentKey());
		paymentValidator.validateApprovable(payment);
		paymentValidator.validateAmounts(command.amount(), order.getTotalAmount(), payment.getAmount());
		paymentValidator.validateDuplicatePayment(order);
		paymentValidator.validateGatewayResponseMatchesRequest(gatewayResult, command);

		order.completePayment();
		bookingCreator.createBookingFromOrder(order);
		payment.approve(gatewayResult.method());
		attempt.markSucceeded();

		paymentOutboxRepository.save(buildBookingConfirmedOutbox(payment, pendingBookings));
		return PaymentConfirmResult.from(payment);
	}

	private PaymentOutbox buildBookingConfirmedOutbox(Payment payment, List<PendingBooking> pendingBookings) {
		String dedupKey = "payment:%d:booking-confirmed".formatted(payment.getId());
		BookingConfirmedPayload payload = BookingConfirmedPayload.from(pendingBookings);
		try {
			String payloadJson = objectMapper.writeValueAsString(payload);
			return PaymentOutbox.forBookingConfirmed(payment.getId(), dedupKey, payloadJson);
		} catch (JacksonException e) {
			throw new BusinessException(PaymentError.PAYMENT_OUTBOX_PAYLOAD_SERIALIZATION_FAILED);
		}
	}
}
