# Payment Consistency

Toss(외부 결제 API), DB, Redis 세 시스템의 상태 정합성을 보장하기 위한 설계. 승인과 취소 흐름에 동일 구조로 적용한다.

## Why

결제 승인과 취소는 외부 API 호출과 내부 상태 변경(DB, Redis)을 함께 수행하는 유일한 흐름이다. 세 시스템은 한 트랜잭션으로 묶을 수 없으므로 특정 지점에서 실패하면 서로 다른 상태로 남는다.

개선 전 `PaymentConfirmService.confirm()`은 클래스 레벨 `@Transactional` 안에서 승인 API 호출과 이후 정리 작업을 함께 실행했다. 두 가지 실패 시나리오가 있었다.

### Scenario 1 — Toss 승인 후 DB 커밋 전 크래시

```
1. Toss confirm() → 승인 성공 (카드 실제 청구)
2. Order.completePayment(), Booking 생성, Payment.approve()
3. ← 서버 크래시 또는 DB 커밋 실패
4. Toss = 승인 완료, DB = 흔적 없음
   → 사용자는 결제했지만 승차권 없음
```

자동 회복 불가. CS 대응과 수동 환불로만 해소된다.

### Scenario 2 — Redis 정리 실패로 승인 롤백

```
1. Toss 승인 성공
2. Payment.approve(), Order.completePayment(), Booking 생성 완료
3. Redis 정리(PendingBooking 삭제, Seat Hold 해제) 중 예외 발생
4. @Transactional이 전체 롤백
5. Toss = 승인, DB = 아무 것도 없음 (Scenario 1과 동일)
```

`deletePendingBookings`는 `try/catch`로 무음 처리, `releaseSeats`는 catch 없이 트랜잭션에 예외 전파. 이슈 #257에서 지적된 지점. 현재는 정리 전체가 트랜잭션 B 커밋 이후로 이동해 승인 결과를 되돌리지 않는다.

### 취소 흐름도 대칭

- Toss 취소 후 DB 커밋 전 크래시 → 환불 완료, 승차권 유효 상태로 잔존
- DB 취소 커밋 후 Redis 좌석 해제 실패 → 판매 가능 좌석이 hold 상태로 잔존

승인과 취소를 같은 정합성 프레임으로 다룬다.

## Why not events only

Spring `ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)`로 정리를 트랜잭션 밖으로 옮기면 Scenario 2 롤백 위험은 해소된다. 그러나 두 가지가 남는다.

- 리스너 실행 전에 프로세스가 죽으면 이벤트 유실. 재시도 없음.
- Scenario 1은 이벤트 발행 자체가 없었던 상황이라 리스너 방식으로는 감지 불가.

이벤트 대신 DB 레코드 두 종류로 대체한다.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│ Toss 호출 전                                            │
│   payment_attempt INSERT (status=IN_PROGRESS)           │
│                                                         │
│ Toss 호출 → 승인 또는 취소 성공                         │
│                                                         │
│ 결제 확정 트랜잭션                                      │
│   Payment / Order / Booking 상태 변경                   │
│   payment_attempt.status = SUCCEEDED                    │
│   payment_outbox INSERT (BookingConfirmed / Cancelled)  │
│ ← 커밋                                                  │
│                                                         │
│ OutboxWorker (별도 스케줄러)                            │
│   payment_outbox 폴링 → Redis 정리 → status=DONE        │
│                                                         │
│ PaymentRecoveryWorker (별도 스케줄러)                   │
│   IN_PROGRESS attempt 폴링 → Toss 조회 API로 대사       │
│   → 롤포워드 완료 또는 보상                             │
└─────────────────────────────────────────────────────────┘
```

## Components

### payment_attempt

Toss 호출 시도와 결과를 기록한다. 승인과 취소를 함께 담는 공용 테이블이며 `attempt_type` 컬럼으로 구분한다.

| Column | Purpose |
|--------|---------|
| `id` | DB PK (IDENTITY) |
| `payment_id` | 대상 결제 (FK 제약 없음, 다른 payment 엔티티 관례 준수) |
| `attempt_id` | 외부 idempotency key (아래 상세) |
| `attempt_type` | `APPROVAL` / `CANCELLATION` |
| `status` | `IN_PROGRESS` / `SUCCEEDED` / `FAILED` |
| `payment_key` | Toss 발급 결제 키 (Toss 조회 API 호출용) |
| `error_code`, `error_message` | 실패 정보 |
| `processing_owner`, `processing_lease_until` | Recovery Worker 동시 처리 방지 |
| `next_retry_at` | Recovery 재시도 예약 시각 |
| `created_at`, `updated_at` | JPA Auditing |

Toss 호출 직전에 `IN_PROGRESS`로 INSERT. 이 row가 있으면 `PaymentRecoveryWorker`가 "Toss는 성공했으나 우리 쪽 상태 전환이 안 된 요청이 있다"는 사실을 인지한다.

승인과 취소를 공용으로 다루는 이유는 두 가지다. 컬럼 하나로 대칭 구조를 표현할 수 있고, Recovery Worker 로직도 하나로 통일된다. 승인에만 필요한 컬럼이 늘어난다면 별도 테이블 분리를 재검토한다.

#### `attempt_id`를 별도로 두는 이유

`id`(DB PK)가 이미 유일하므로 매 attempt가 새 row인 이상 `id`만으로 식별은 가능하다. 그럼에도 `attempt_id`(String, unique)를 별도 컬럼으로 두는 이유는 세 가지다.

- **내부 재시도 방어.** `startApprovalInNewTransaction`이 INSERT를 마쳤으나 응답이 유실되어 호출측이 재시도하는 경우, 같은 `attempt_id`로 unique 제약이 중복 삽입을 차단한다.
- **클라이언트 Idempotency-Key와의 연결.** 이슈 #260에서 클라이언트가 보내는 `Idempotency-Key` 헤더를 그대로 `attempt_id`에 저장하면 API 계층의 중복 방지와 도메인 계층의 시도 관리가 하나의 키로 이어진다.
- **관심사 분리.** `payment_key`는 Toss가 발급하는 외부 시스템 키, `attempt_id`는 우리 도메인의 시도 참조 키다. 하나로 뭉치면 PG 교체나 시도 이력 확장 시 스키마 변경 범위가 커진다.

현재 승인 API는 요청 body의 `attemptId`를 사용하며, 생략 시 `SHA-256("apv:" + paymentKey)`의
64자리 16진수 문자열을 사용한다. Idempotency-Key 헤더 연동은 #260 범위다.

#### 승인 재요청과 동시 실행 방어

- 같은 attemptId는 `paymentId`, `paymentKey`, `APPROVAL` 타입까지 일치해야 재사용할 수 있다. 불일치하면 `PAYMENT_114`, 64자를 초과한 입력은 API 검증 또는 애플리케이션의 `PAYMENT_115`로 거절한다.
- 성공한 시도는 DB에서 승인 결과 DTO를 직접 조회한다. 이미 로딩한 Payment 엔티티를 그대로 반환하지 않으므로, 다른 트랜잭션이 방금 승인한 결과도 반영한다. 최초 응답을 저장·재생하는 방식은 아니며 환불 등 이후 상태 변경도 반영한다.
- `PaymentAttemptManager.startApprovalInNewTransaction`은 짧은 `REQUIRES_NEW` 트랜잭션에서 Payment 행을 잠그고, 기존 승인 시도와 승인 가능 상태를 확인한 뒤 paymentKey 갱신과 attempt INSERT를 함께 커밋한다. 잠금과 DB 트랜잭션은 Toss 호출 전에 해제한다.
- 반환값 `PaymentAttemptStartResult.created`가 true인 호출만 승인 API를 실행한다. 동일 시도의 재사용은 false로 반환하며, 다른 attemptId를 보내도 진행 중이거나 실패한 기존 승인을 우회할 수 없다.
- 실패한 결제를 재시도하려면 새 주문·결제를 준비한다. 기존 Payment가 FAILED/CANCELLED/REFUNDED이면 외부 호출 전에 거절한다.
- INSERT/커밋 무결성 오류 뒤에는 실제 동일 attempt의 존재와 요청 일치를 확인한다. 다른 제약 위반은 원래 오류로 전파하고 paymentKey와 attempt를 함께 롤백한다.
- Toss의 4xx 응답은 확정 실패로 분류해 Payment와 attempt를 FAILED로 전환한다. 5xx, 타임아웃, 응답 유실처럼 승인 여부를 단정할 수 없는 오류는 attempt를 IN_PROGRESS로 유지하고 Recovery Worker가 조회한다.
- Toss 성공 후 `PaymentApprovalFinalizer`가 새 트랜잭션에서 Payment를 다시 잠그고 Order/Booking/Payment/Attempt/Outbox를 원자적으로 확정한다. 외부 호출 전에 읽은 엔티티는 확정에 재사용하지 않는다.

Task 10의 Recovery는 새 승인을 시작하지 않고 기존 IN_PROGRESS를 확정해야 한다.
복구와 승인 확정이 경합하는 경우의 상태 재검증·처리 권한 확보도 해당 Task에서 검증한다.

### payment_outbox

DB 커밋 이후 Redis에 반영해야 할 작업을 기록한다. 결제 확정 트랜잭션 안에서 INSERT하므로 DB 상태 변경과 원자적으로 커밋된다.

| Column | Purpose |
|--------|---------|
| `id` | DB PK |
| `type` | `BOOKING_CONFIRMED` / `BOOKING_CANCELLED` |
| `aggregate_id` | 소비자 컨텍스트 (payment_id 등) |
| `deduplication_key` | 재발행 시 중복 처리 방지 (unique) |
| `payload` | Redis 정리에 필요한 최소 정보 (JSON) |
| `status` | `PENDING` / `DONE` / `FAILED` |
| `retry_count`, `next_retry_at` | Worker 재시도 상태 |
| `created_at`, `processed_at` | 감사 |

Payload는 Redis 정리 지점을 특정할 수 있는 최소 정보만 담는다. 예: `pendingBookingIds`, `seatIds`, `trainCarId`, `stopOrders`. 취소는 부분 취소 대비 `cancelledSeatSections[]`까지 포함한다.

### OutboxWorker

애플리케이션 내부 스케줄러로 시작. 다중 인스턴스 동시 실행에 대비해 `SELECT ... FOR UPDATE SKIP LOCKED` 또는 `processing_owner` 임차 방식으로 처리 권한을 확보한다.

- 정상: Redis 정리 실행 → `status=DONE`
- 실패: `retry_count++`, `next_retry_at = now + backoff` 갱신
- 최대 재시도 초과: `status=FAILED` 전환, 알람

### PaymentRecoveryWorker

`IN_PROGRESS` 상태이면서 `updated_at`이 임계값을 넘긴 `payment_attempt`를 조회한다. Toss 결과 조회 API로 실제 상태를 확인한 뒤 다음 중 하나로 확정한다.

| Toss 상태 | 처리 |
|----------|------|
| 성공 + 우리 DB 미확정 | **롤포워드**: 결제 확정 트랜잭션 재실행 + outbox INSERT |
| 실패 | **보상**: `payment_attempt.status=FAILED`, `Payment.fail()` |
| 미확정 | 다음 폴링까지 대기 |

## Transaction Boundary

```
[트랜잭션 A — Toss 호출 전]
  Payment SELECT FOR UPDATE
  payment.payment_key 갱신
  payment_attempt INSERT (IN_PROGRESS)
  COMMIT

[트랜잭션 밖]
  Toss 호출 (외부 I/O)

[트랜잭션 B — 승인 확정]
  Payment SELECT FOR UPDATE + 최신 상태 재검증
  Order / Payment / Booking 상태 변경
  payment_attempt.status = SUCCEEDED
  payment_outbox INSERT
  COMMIT

[트랜잭션 밖 — 임시 인라인, Worker 이관 예정]
  Redis 정리

[트랜잭션 밖 — Recovery Worker]
  IN_PROGRESS attempt의 Toss 상태 조회 및 복구
```

승인 자체의 원자성(Payment/Order/Booking)은 트랜잭션 B가 보장한다. 실패 회복은 Worker가 담당한다. Redis 정리는 어떤 경로에서도 승인 트랜잭션을 롤백시키지 않는다.

`PaymentConfirmService.confirm()`은 자체 트랜잭션을 열지 않고 위 세 단계를 순서대로 연결하기만 한다.

| 단계 | 담당 | 트랜잭션 |
|---|---|---|
| 승인 시작 | `PaymentApprovalStarter.start` | 조회는 각 컴포넌트의 짧은 트랜잭션, 커밋은 아래 한 곳뿐 |
| └ 트랜잭션 A |  `PaymentAttemptManager.startApprovalInNewTransaction` | `REQUIRES_NEW` |
| Toss 승인 요청 | `PaymentGateway.confirm` | 없음 |
| 트랜잭션 B | `PaymentApprovalFinalizer.finalizeApproval` | `REQUIRED` |

## Failure Coverage

| 실패 지점 | 대응 |
|----------|------|
| Toss 승인 후 DB 커밋 전 크래시 | `PaymentRecoveryWorker`가 `IN_PROGRESS` attempt를 Toss 조회로 확정 |
| DB 커밋 후 Redis 정리 실패 | `OutboxWorker`가 `PENDING` outbox 행 재시도 |
| Redis 정리 중 일시 오류 | outbox 재시도, `retry_count` 초과 시 알람 |
| Toss 취소 후 DB 커밋 전 크래시 | Recovery Worker가 취소 attempt 확정 |
| DB 취소 커밋 후 좌석 해제 실패 | outbox 재시도로 좌석 해제 최종 반영 |

## Metrics

| Name | Type | Purpose |
|------|------|---------|
| `payment.attempt.in_progress` | Gauge | 진행 중 attempt 수 |
| `payment.attempt.recovered` | Counter | Recovery Worker가 복구한 건수 |
| `payment.outbox.pending` | Gauge | 미처리 outbox 행 수 |
| `payment.outbox.failed` | Counter | 최대 재시도 초과 건수 |
| `payment.cleanup.failure` | Counter | Redis 정리 예외 발생 |

## Alternatives

| 방식 | Scenario 1 | Scenario 2 | 프로세스 크래시 회복 | 관측/재시도 |
|------|-----------|-----------|-------------------|-----------|
| 현재 (`@Transactional`에 정리 포함) | 미커버 | 미커버 (롤백 위험) | 불가 | 없음 |
| `AFTER_COMMIT` 이벤트 | 미커버 | 커버 | 불가 (이벤트 유실) | 리스너 로그만 |
| PaymentAttempt + Outbox | 커버 | 커버 | 가능 | 지표와 재시도 이력 |

## Rollout

### PR 2 후속 작업 범위 (2026-09-11)

`refactor/257-payment-consistency-worker`의 Tasks 8–12에서 다음 작업을 이어간다.

- **Task 8–9:** `PaymentConfirmService.cleanupPendingBookings` 호출과 구현을 제거하고 OutboxWorker에 처리·재시도·최대 시도 초과 정책을 연결한다. 이번 PR의 처리기는 **NoOp**이며 실제 Redis 정리는 새 스키마 확정 후 별도 이슈에서 구현한다. 따라서 이번 PR의 outbox DONE은 Redis 정리 완료를 보장하지 않는다.
- **Task 10:** 네트워크 타임아웃·응답 유실, Toss 성공 후 DB 확정/커밋 실패로 남은 `IN_PROGRESS`를 Toss 조회로 대사한다. 결과가 미확정이면 실패로 단정하거나 승인 API를 재호출하지 않고 다음 폴링까지 유지한다.
- **Task 11–12:** 미처리·실패·복구 지표와 통합 테스트를 추가한다. 복구 후 같은 attemptId 재요청에 확정 결과가 반환되는지 검증하고, NoOp 처리기의 한계를 문서에 유지한다.

승인 재요청의 요청 일치 검증, 최신 결과 조회, 결제별 동시 승인 차단, 승인 가능 상태 검증,
입력 검증과 DB 무결성 오류 구분은 Worker 도입으로 해결되지 않으므로 선행 수정한다.

### 적용 순서

1. 승인 흐름에 `payment_attempt`와 `payment_outbox` 도입 (이슈 #257)
2. `OutboxWorker`, `PaymentRecoveryWorker` 신설
3. 취소 흐름에 대칭 적용 (이슈 #259)
4. 기존 `try/catch` 무음 처리 및 인라인 cleanup 제거
5. 관측 지표와 알람 추가

## Future Extensions

각 항목은 필요 시점에 별도 이슈로 다룬다.

### 클라이언트 Idempotency-Key 헤더

`payment_attempt`는 서버 재시도의 멱등성을 다룬다. 클라이언트가 결제 버튼을 두 번 누르는 케이스는 API 진입 지점에서 `Idempotency-Key` 헤더를 Redis에 캐시하는 별도 층으로 처리한다. (이슈 #260)

### Toss 웹훅 리스너

Toss는 승인과 취소 결과를 API 응답과 웹훅으로 이중 통지한다. 현재 설계는 API 응답과 폴링만 사용한다. 웹훅을 세 번째 대사 채널로 추가하면 응답 유실 시 회복 지연이 줄어든다. 정합성 자체는 폴링만으로 확보되며 웹훅은 실시간성 개선용이다.

### Redis Streams 기반 알림 fan-out

알림 채널이 여러 개로 확장되면 `payment_outbox` 발행 채널을 Redis Streams로 확장한다. Outbox INSERT는 그대로 유지하고 Publisher가 로컬 소비자와 Redis Streams topic에 함께 발행한다. 채널이 하나뿐이면 도입하지 않는다.

### 시간 기반 알림 (출발 전 알림 등)

"출발 30분 전" 같은 예약 기반 발송은 이벤트 스트림과 성격이 다르다. 스케줄 outbox 테이블 또는 Redis ZSET(score = 발송 시각) 기반 Worker로 별도 처리한다.

### MSA 분리 시점의 브로커 재검토

Auth와 Payment 등이 별도 서비스로 분리되면 서비스 경계를 넘는 이벤트가 필요해진다. 이 시점에 Kafka(자체 호스팅 또는 OCI Streaming) 도입을 재검토한다. `Outbox → Kafka Publisher` 패턴으로 확장하면 기존 코드 구조 변경이 최소화된다.

## Out of Scope

- 결제 승인과 취소 자체의 비즈니스 규칙 (환불 조건, 금액 계산 등)
- 좌석 충돌 검증 4-Layer 방어 (`docs/seat-conflict-validation.md`)
- 예약(PendingBooking) 만료 처리 방식 개편
