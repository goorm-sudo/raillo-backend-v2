package com.sudo.raillo.payment.application.result;

/** 새 승인 시도를 획득한 호출만 외부 승인 API를 실행한다. */
public record PaymentAttemptStartResult(Long attemptDbId, boolean created) {
}
