package com.sudo.raillo.payment.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * PaymentAttempt의 외부 idempotency key(`attempt_id`)를 파생하는 규칙 모음.
 *
 * <p>같은 입력에 대해 같은 attempt_id를 만들어 unique 제약이 서버 측 멱등성 역할을 하도록 한다.
 * 승인은 paymentKey당 1개, 취소는 paymentKey당 N개(부분 취소)이므로 취소는 sequence로 구분한다.
 */
public final class PaymentAttemptIds {

	private PaymentAttemptIds() {
	}

	/**
	 * 승인 attempt의 attempt_id.
	 * 같은 paymentKey에 대한 재시도는 같은 값을 반환한다.
	 */
	public static String forApproval(String paymentKey) {
		return sha256Hex("apv:" + paymentKey);
	}

	/**
	 * 취소 attempt의 attempt_id.
	 * 같은 paymentKey라도 cancellationSequence가 다르면 다른 값을 반환하므로,
	 * 부분 취소가 여러 번 발생해도 unique 제약과 충돌하지 않는다.
	 */
	public static String forCancellation(String paymentKey, int cancellationSequence) {
		return sha256Hex("cnl:" + paymentKey + ":" + cancellationSequence);
	}

	private static String sha256Hex(String input) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
				.digest(input.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 사용 불가", e);
		}
	}
}
