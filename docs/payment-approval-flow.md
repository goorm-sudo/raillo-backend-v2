# Payment Approval Flow
## 기존 결제 로직의 문제점

`confirm()`은 클래스 레벨 `@Transactional`과 메서드 레벨 `@Transactional(isolation = READ_COMMITTED)` 아래에서 조회·검증·Toss 호출·DB 확정·Redis 정리를 모두 실행

```
외부 TX (READ_COMMITTED)
├─ Order / Member / Payment 조회, 소유권·금액 검증
├─ [REQUIRES_NEW] Payment 잠금 + paymentKey + attempt(IN_PROGRESS) 커밋
├─ Toss 승인 API 호출          ← 외부 TX가 열린 채로 대기
├─ Order / Booking / Payment / Attempt / Outbox 변경
├─ Redis 정리 (PendingBooking 삭제, Seat Hold 해제)
└─ COMMIT
```

### 1. 외부 API 호출이 DB 트랜잭션 안에 있어 커넥션 풀 점유 문제 발생

- `paymentGateway.confirm()`이 외부 트랜잭션 안에서 실행
- `REQUIRES_NEW`의 비관 락은 Toss 호출 전에 풀리지만, 바깥 트랜잭션과 DB 커넥션은 Toss 응답을 기다리는 내내 유지

Toss가 느려지면 트랜잭션 유지 시간이 그대로 외부 API 지연에 종속되고, 동시 결제가 몰리면 실제 DB 작업을 하지 않는 커넥션이 풀을 점유하는 문제 발생

### 2. 결과를 알 수 없는 오류를 확정 실패로 기록

`TossPaymentException`을 잡으면 HTTP 상태와 무관하게 attempt와 Payment를 `FAILED`로 전환

```java
} catch (TossPaymentException e) {
    paymentAttemptManager.markFailedInNewTransaction(attemptDbId, e.getErrorCode(), e.getMessage());
    paymentModifier.failPaymentInNewTransaction(payment.getId(), e.getErrorCode(), e.getMessage());
    throw e;
}
```

`TossPaymentException`은 카드 거절 같은 4xx뿐 아니라 5xx, 빈 오류 본문(`EMPTY_ERROR_BODY`), 파싱 실패(`UNPARSABLE_ERROR_BODY`)에도 던져진다.
Toss가 승인을 마친 뒤 응답 전송에 실패한 경우까지 `FAILED`로 확정되면, Recovery Worker가 찾아야 할 "결과 불명" 건이 사라진다.

### 3. 외부 호출 전에 읽은 엔티티를 확정에 재사용했다

`order`, `payment`는 Toss 호출 이전에 로드한 인스턴스인데, 확정 단계에서 그대로 `order.completePayment()`, `payment.approve()`를 호출했다.
Toss 응답을 기다리는 동안 다른 트랜잭션이 같은 행을 바꿨더라도 재검증 없이 덮어쓴다.

## 결제 로직 수정
`confirm()`에서 `@Transactional`을 제거하고, 세 단계를 순서대로 연결하는 오케스트레이터로 축소

```java
// 1. TX A - 승인 시작
PaymentApprovalStart start = paymentApprovalStarter.start(command, memberNo);
if (start.isAlreadyConfirmed()) {
    return start.previousResult();
}

// 2. Toss 승인 요청 (DB 트랜잭션 없음)
GatewayConfirmResult approval = requestTossApproval(command, start.paymentId(), start.attemptDbId());

// 3. TX B - 승인 확정
PaymentConfirmResult result = paymentApprovalFinalizer.finalizeApproval(
    start.paymentId(), start.attemptDbId(), command, approval, start.pendingBookings()
);

// TX B 커밋 이후 실행한다. 후속 PR에서 OutboxWorker로 완전히 이관한다.
cleanupPendingBookings(start.pendingBookings());
```

### 승인 시작

`PaymentApprovalStarter.start()`가 Toss 호출에 필요한 조회·검증을 마치고, `PaymentAttemptManager.startApprovalInNewTransaction()`(TX A)으로 `IN_PROGRESS` attempt를 커밋

```
attemptId 결정 (요청값 또는 SHA-256("apv:" + paymentKey))
  → Order / Member / Payment 조회
  → 소유권·금액 검증
  → 같은 attemptId의 기존 attempt 확인 (멱등 처리)
  → 승인 가능 상태 검증
  → PendingBooking 조회 (Redis)
  → 중복 결제 검증
  → [TX A] Payment 잠금 + paymentKey + attempt(IN_PROGRESS) 커밋
```

`start()` 자체는 트랜잭션을 열지 않는다. 각 조회는 해당 컴포넌트의 짧은 트랜잭션에서 수행되고, Redis 조회도 DB 트랜잭션 밖에 둔다. 커밋 지점은 TX A 하나뿐이다.

기존 attempt가 있으면 Toss를 호출하지 않고 조기 종료한다.

| attempt 상태 | 응답 |
|---|---|
| `SUCCEEDED` | DB에서 최신 승인 결과를 재조회해 반환 |
| `FAILED` | `PAYMENT_ATTEMPT_ALREADY_FAILED` |
| `IN_PROGRESS` | `PAYMENT_ATTEMPT_IN_PROGRESS` |

### 승인 요청

`PaymentConfirmService.requestTossApproval()`이 트랜잭션이 없는 상태에서 `paymentGateway.confirm()`을 호출
TX A를 획득한(`created == true`) 요청만 이 지점에 도달

실패는 두 종류로 나눈다.

```java
if (!exception.isDefinitiveFailure()) {
    // 5xx — 결과 불명. attempt를 IN_PROGRESS로 유지한다.
    return;
}
// 4xx — 확정 실패. attempt와 Payment를 FAILED로 마킹한다.
```

### 승인 확정

`PaymentApprovalFinalizer.finalizeApproval()`(TX B)이 DB 상태를 원자적으로 확정한다.

```
[TX B]
Payment SELECT FOR UPDATE (재조회)
Order / PaymentAttempt 재조회
  → attempt·승인 가능 상태·금액·중복 결제·게이트웨이 응답 일치 재검증
  → Order = ORDERED
  → Booking / Ticket 생성
  → Payment = PAID
  → PaymentAttempt = SUCCEEDED
  → PaymentOutbox(BOOKING_CONFIRMED) INSERT
COMMIT
```
외부 호출 전에 읽은 엔티티는 쓰지 않는다. Toss를 기다리는 동안 상태가 바뀌었을 수 있으므로 Payment를 다시 잠그고 Order와 attempt를 최신 상태로 읽어 재검증한다.

## 각 단계별 역할

| 단계 | 담당 | 역할 | 실패 시 외부 부작용 |
|---|---|---|---|
| 승인 시작 | `PaymentApprovalStarter.start` | 조회·검증·멱등 처리, TX A 위임 | 없음 (Toss 미호출) |
| └ TX A | `PaymentAttemptManager.startApprovalInNewTransaction` | Payment 잠금, paymentKey 저장, attempt `IN_PROGRESS` 커밋 | 없음 |
| 승인 요청 | `PaymentGateway.confirm` | Toss 승인 API 호출, 오류 분류 | 카드 청구 발생 가능 |
| 승인 확정 | `PaymentApprovalFinalizer.finalizeApproval` | 재조회·재검증 후 DB 상태 원자적 확정 | 없음 (Toss 상태 불변) |
| 정리 | `PaymentConfirmService.cleanupPendingBookings` | PendingBooking 삭제, Seat Hold 해제 | 없음 (TTL로 회수) |

`PaymentConfirmService`는 이 단계들을 연결하고 승인 요청의 오류를 분류하는 일만 한다.

## 트랜잭션의 범위

```
[TX A - 승인 시작] REQUIRES_NEW
  Payment SELECT FOR UPDATE
  payment.payment_key 갱신
  payment_attempt INSERT (IN_PROGRESS)
  COMMIT ────────────────────────── 잠금 해제, 트랜잭션 종료

[트랜잭션 없음 - 승인 요청]
  Toss 승인 API 호출

[TX B - 승인 확정] REQUIRED
  Payment SELECT FOR UPDATE + 최신 상태 재검증
  Order / Payment / Booking 상태 변경
  payment_attempt.status = SUCCEEDED
  payment_outbox INSERT
  COMMIT
```

`confirm()`에는 트랜잭션이 없으므로 TX B는 TX A가 완전히 끝난 뒤 새로 시작한다. TX A의 커밋이 TX B 시작 시점부터 보이므로, 기존 구조에서 필요했던 `READ_COMMITTED` 명시가 더 이상 필요 없다.

TX A와 확정 실패 마킹은 `REQUIRES_NEW`를 유지한다. 현재는 호출자에 트랜잭션이 없어 `REQUIRED`와 동작이 같지만, 나중에 `confirm()` 위에 트랜잭션이 생기더라도 "Toss 호출 전에 attempt가 커밋되어 있어야 한다"는 불변식이 깨지지 않도록 하는 안전장치다.

## 예외 케이스

### 1. 승인 시작이 실패하는 경우

Toss를 호출하기 전이므로 외부 부작용이 없다. 사용자는 안전하게 재시도할 수 있다.

| 원인 | 응답 |
|---|---|
| attemptId 64자 초과 | `PAYMENT_115` |
| 주문·결제 소유자 불일치 | `PAYMENT_ACCESS_DENIED` |
| 요청 금액과 주문·결제 금액 불일치 | 금액 검증 실패 |
| 같은 attemptId가 다른 paymentId·paymentKey로 재사용됨 | `PAYMENT_114` |
| 같은 attemptId가 이미 `IN_PROGRESS` / `FAILED` | `PAYMENT_ATTEMPT_IN_PROGRESS` / `PAYMENT_ATTEMPT_ALREADY_FAILED` |
| PendingBooking 없음 또는 만료 | `PENDING_BOOKING_IDS_REQUIRED` 등 |
| Payment가 이미 FAILED/CANCELLED/REFUNDED | 승인 가능 상태 검증 실패 |
| 동시 요청 중 잠금 경쟁에서 패배 | `PAYMENT_ATTEMPT_IN_PROGRESS` |
| `attempt_id` unique 제약 충돌 | 실제 attempt를 재확인 후 멱등 응답 |

TX A가 실패하면 paymentKey 갱신과 attempt INSERT가 같은 트랜잭션이므로 함께 롤백된다. Payment는 `PENDING`, attempt는 생성되지 않은 상태로 남는다.

`DataIntegrityViolationException`은 특별히 처리한다. TX A가 롤백된 뒤 같은 attemptId가 실제로 존재하는지 다시 조회해, 존재하면 멱등 응답으로 전환하고 아니면 원래 오류를 전파한다.

```
요청 A ─┐
        ├─ 동시에 TX A 진입
요청 B ─┘
  → 한쪽만 attempt 생성 (created = true) → Toss 호출
  → 다른 쪽은 unique 충돌 또는 잠금 대기 후 기존 attempt 확인 → Toss 미호출
```

### 2. 승인 요청이 실패하거나 시간이 오래 걸리는 경우

TX A는 이미 커밋됐으므로 어떤 경우에도 `IN_PROGRESS` attempt 기록이 남는다.

**4xx — 확정 실패**

카드 거절처럼 승인되지 않은 것이 확실한 경우다.

```
PaymentAttempt = FAILED (errorCode, errorMessage 기록)
Payment        = FAILED
Order          = PENDING (TX B 미진입)
Redis          = PendingBooking·Seat Hold 그대로 (TTL로 회수)
```

두 마킹은 각각 `REQUIRES_NEW`로 커밋되므로 이후 예외 전파와 무관하게 남는다.

**5xx·빈 응답·파싱 실패 — 결과 불명**

Toss가 승인을 마친 뒤 응답에 실패했을 수 있다.

```
PaymentAttempt = IN_PROGRESS 유지 (errorCode 기록 안 함)
Payment        = PENDING
Order          = PENDING
```

`FAILED`로 확정하지 않는 이유는 확정하는 순간 Recovery 대상에서 빠지고, 같은 attemptId 재요청도 `PAYMENT_ATTEMPT_ALREADY_FAILED`로 차단되기 때문이다.

**타임아웃·네트워크 오류**

`TossPaymentClient`가 `BusinessException(PAYMENT_SYSTEM_ERROR)`으로 감싸므로 `catch (TossPaymentException)`에 걸리지 않는다. attempt는 손대지 않은 채 `IN_PROGRESS`로 남는다. 결과 불명과 동일한 처리다.

타임아웃은 우리 서버가 기다리기를 멈추는 것이지 Toss의 승인을 취소하는 것이 아니므로, 같은 요청을 재호출하지 않고 조회 API로 대사해야 한다.

**복구**

`IN_PROGRESS`로 남은 attempt는 `PaymentRecoveryWorker`(#257 Task 10, 미구현)가 `payment_key`로 Toss 상태를 조회해 확정한다.

| Toss 조회 결과 | 처리 |
|---|---|
| 승인됨 | 롤포워드 — TX B 재실행 + outbox INSERT |
| 실패 확정 | 보상 — attempt `FAILED`, `Payment.fail()` |
| 미확정 | 다음 폴링까지 대기 |

### 3. 승인 시작과 승인 요청이 성공했지만 승인 확정이 실패한 경우

Toss에서는 이미 청구가 발생한 상태다. TX B가 실패하면 그 안의 DB 변경만 전부 롤백된다.

```
Toss           = 승인 완료 (롤백 불가)

롤백됨 (TX B)
  Order          = PENDING 유지
  Booking/Ticket = 생성 취소
  Payment        = PENDING 유지 (paidAt null)
  PaymentAttempt = SUCCEEDED 전환 취소
  PaymentOutbox  = INSERT 취소

살아남음 (TX A에서 이미 커밋)
  Payment.paymentKey = 저장됨
  PaymentAttempt     = IN_PROGRESS

Redis
  PendingBooking / Seat Hold = 그대로 유지 (정리 미실행)
```

Redis 정리가 TX B 커밋 이후로 이동했기 때문에, 확정이 실패하면 정리 단계에 아예 도달하지 않는다. 기존 구조에서 발생하던 "일부만 정리된 상태"가 생기지 않는다.

사용자는 오류 응답을 받고, 같은 attemptId로 재요청하면 `PAYMENT_ATTEMPT_IN_PROGRESS`를 받는다. 다른 attemptId를 보내도 `findLatestApprovalByPaymentId`가 진행 중인 승인을 찾아 차단한다. 즉 클라이언트 재시도로는 복구되지 않으며, Recovery Worker의 롤포워드가 복구 경로다.

TX A가 Toss 호출 전에 커밋되는 이유가 여기에 있다. 이 기록이 없으면 Toss에서 청구된 결제를 찾아낼 단서가 남지 않는다.

이 동작은 `PaymentConfirmServiceTest.confirmPayment_finalizationFailure_rollsBackDatabaseChanges`가 검증한다. Outbox 저장을 실패시켰을 때 Order·Payment·Booking·Outbox가 모두 롤백되고 attempt는 `IN_PROGRESS`로, PendingBooking은 Redis에 남는지 확인한다.
