# 결제 정합성 설계

Toss(외부 결제 API), 애플리케이션 DB, Redis 세 시스템 사이의 상태 정합성을 확보하기 위한 설계입니다. 승인 흐름과 취소 흐름 양쪽에 동일한 구조로 적용합니다.

## 왜 필요한가

결제 승인과 취소는 외부 API 호출과 내부 상태 변경(DB, Redis)을 함께 수행하는 유일한 흐름입니다. 세 시스템은 한 트랜잭션으로 묶을 수 없기 때문에, 특정 지점에서 실패하면 서로 다른 상태로 남습니다.

현재 `PaymentConfirmService.confirm()`은 클래스 레벨 `@Transactional` 안에서 승인 API 호출 이후의 정리 작업을 함께 실행하며, 다음 두 가지 실패 시나리오를 안고 있습니다.

### 시나리오 1 — Toss 승인 이후 DB 커밋 전 크래시

```
1. Toss confirm() 호출 → 승인 성공 (카드 실제 청구)
2. Order.completePayment(), Booking 생성, Payment.approve() 실행
3. ← 이 지점에서 서버 크래시 또는 DB 커밋 실패
4. 결과: Toss는 승인과 청구가 완료된 상태, 우리 DB에는 흔적이 없음
        사용자는 돈을 냈지만 승차권을 받지 못함
```

무엇으로도 자동 회복되지 않으며 CS 대응과 수동 환불로만 해소됩니다.

### 시나리오 2 — Redis 정리 실패로 인한 승인 롤백

```
1. Toss 승인 성공
2. Payment.approve(), Order.completePayment(), Booking 생성 완료
3. Redis 정리 (PendingBooking 삭제, Seat Hold 해제) 중 예외 발생
4. @Transactional이 예외를 잡아 전체 롤백
5. 결과: Toss는 승인, DB는 아무 것도 없음 (시나리오 1과 동일 상태)
```

현재 코드에서는 `deletePendingBookings`는 `try/catch`로 예외를 무음 처리하고, `releaseSeats`는 catch 없이 트랜잭션에 예외를 전파합니다. 이슈 #257에서 처음 지적된 지점입니다.

### 취소 흐름도 대칭

취소와 환불도 구조가 동일합니다.

- Toss 취소 API 호출 이후 DB 커밋 전 크래시 → 환불은 되었으나 승차권이 유효한 상태로 남습니다.
- DB 취소 커밋 후 Redis 좌석 해제 실패 → 판매 가능한 좌석이 계속 hold 상태로 남습니다.

승인과 취소를 같은 정합성 프레임으로 다룹니다.

## 왜 이벤트만으로는 부족한가

Spring `ApplicationEventPublisher`와 `@TransactionalEventListener(phase = AFTER_COMMIT)`로 정리 작업을 트랜잭션 밖으로 옮기면 시나리오 2의 롤백 위험은 사라집니다. 다만 다음 두 지점이 여전히 남습니다.

- 리스너 실행 전에 프로세스가 죽으면 이벤트가 유실됩니다. 재시도 없이 사라집니다.
- 시나리오 1(Toss 승인 후 DB 커밋 전 크래시)은 이벤트 발행 자체가 없었던 상황이라 리스너 방식으로는 감지할 수 없습니다.

따라서 이벤트 대신 DB 레코드 두 종류로 대체합니다.

## 구성 요소

```
┌────────────────────────────────────────────────────────────┐
│  Toss 호출 전                                              │
│    payment_attempt 행 INSERT (status=IN_PROGRESS)          │
│                                                            │
│  Toss 호출 → 승인 또는 취소 성공                           │
│                                                            │
│  결제 확정 트랜잭션                                        │
│    Payment, Order, Booking 상태 변경                       │
│    payment_attempt.status = SUCCEEDED                      │
│    outbox INSERT (BookingConfirmed 또는 BookingCancelled)  │
│  ← 커밋                                                    │
│                                                            │
│  OutboxWorker (별도 스케줄러)                              │
│    outbox 폴링 → Redis 정리 → status=DONE                  │
│                                                            │
│  PaymentRecoveryWorker (별도 스케줄러)                     │
│    IN_PROGRESS attempt 폴링 → Toss 조회 API로 결과 확인    │
│    → 확정 또는 실패 보상                                   │
└────────────────────────────────────────────────────────────┘
```

### payment_attempt

Toss 호출과 그 결과를 기록합니다. 승인과 취소 양쪽을 함께 담기 위해 `attempt_type` 컬럼으로 구분하는 공용 테이블로 시작합니다.

```
id
payment_id
attempt_type          -- APPROVAL 또는 CANCELLATION
attempt_id            -- 멱등 키 (재시도 시 동일 값 재사용)
status                -- IN_PROGRESS, SUCCEEDED, FAILED
payment_key
error_code
error_message
processing_owner      -- 복구 Worker가 동시 처리 방지에 사용
processing_lease_until
next_retry_at
created_at
updated_at
```

Toss 호출 직전에 `IN_PROGRESS`로 INSERT합니다. 이 행이 있으면 `PaymentRecoveryWorker`가 "Toss는 성공했는데 우리 쪽 상태 전환이 안 된 요청이 있다"는 사실을 알 수 있습니다.

승인과 취소를 공용으로 다루는 방식을 권장합니다. 컬럼 하나로 대칭 구조를 표현할 수 있고 복구 Worker 로직도 하나로 통일됩니다. 승인에만 필요한 컬럼이 늘어난다면 별도 테이블 분리를 재검토합니다.

### outbox

DB 커밋 이후 Redis에 반영해야 할 작업을 기록합니다. 결제 확정 트랜잭션 안에서 INSERT하므로 DB 상태 변경과 원자적으로 커밋됩니다.

```
id
type                  -- BookingConfirmed 또는 BookingCancelled
aggregate_id          -- payment_id 또는 order_id
deduplication_key     -- 재시도 시 중복 처리 방지
payload               -- 정리에 필요한 최소 정보 (JSON)
status                -- PENDING, DONE, FAILED
retry_count
next_retry_at
created_at
processed_at
```

Payload에는 Redis 정리 지점을 특정할 정보만 담습니다. 예를 들어 `pendingBookingIds`, `seatIds`, `trainCarId`, `stopOrders` 등입니다. 취소의 경우 부분 취소를 대비해 `cancelledSeatSections[]`까지 포함합니다.

### OutboxWorker

애플리케이션 내부 스케줄러로 시작합니다. 여러 인스턴스에서 동시 실행되어도 안전하도록 `SELECT ... FOR UPDATE SKIP LOCKED` 또는 `processing_owner` 임차 방식으로 처리 권한을 확보합니다.

- 정상 처리: Redis 정리 실행 → `status=DONE`
- 실패: `retry_count++`, `next_retry_at = now + backoff` 갱신 후 재시도
- 최대 재시도 초과: `status=FAILED` 전환 후 알람 발송

### PaymentRecoveryWorker

`IN_PROGRESS` 상태이면서 `updated_at`이 임계값을 넘긴 `payment_attempt`를 조회합니다. Toss 결과 조회 API로 실제 승인 또는 취소 상태를 확인한 뒤 다음 중 하나로 확정합니다.

- Toss 성공 + 우리 DB 미확정 → 결제 확정 트랜잭션 재실행 + outbox INSERT
- Toss 실패 → `payment_attempt.status = FAILED` + Payment 실패 처리
- Toss 미확정 → 다음 폴링까지 대기

## 트랜잭션 경계

```
[트랜잭션 밖]
  Toss 호출 (외부 I/O)

[트랜잭션 A — Toss 호출 전]
  payment_attempt INSERT (IN_PROGRESS)

[트랜잭션 B — 승인 또는 취소 확정]
  Order, Payment, Booking 상태 변경
  payment_attempt.status = SUCCEEDED
  outbox INSERT

[트랜잭션 밖 — Worker]
  Redis 정리
  Toss 조회 및 복구
```

승인 자체의 원자성(Payment, Order, Booking)은 트랜잭션 B가 보장하고, 실패 회복은 Worker가 담당합니다. Redis 정리는 어떤 경로에서도 승인 트랜잭션을 롤백시키지 않습니다.

## 실패 시나리오 대응 정리

| 실패 지점 | 대응 |
|-----------|------|
| Toss 승인 후 DB 커밋 전 크래시 | `PaymentRecoveryWorker`가 `IN_PROGRESS` attempt를 Toss 조회로 확정 |
| DB 커밋 후 Redis 정리 실패 | `OutboxWorker`가 `PENDING` outbox 행을 재시도 |
| Redis 정리 중 일시 오류 | outbox 재시도, `retry_count` 초과 시 알람 |
| Toss 취소 후 DB 커밋 전 크래시 | Recovery Worker가 취소 attempt를 확정 |
| DB 취소 커밋 후 좌석 해제 실패 | outbox 재시도로 좌석 해제 최종 반영 |

## 관측

- `payment.attempt.in_progress` — 진행 중 attempt 수 (Gauge)
- `payment.attempt.recovered` — Recovery Worker가 복구한 건수 (Counter)
- `payment.outbox.pending` — 미처리 outbox 행 수 (Gauge)
- `payment.outbox.failed` — 최대 재시도 초과 건수 (Counter)
- `payment.cleanup.failure` — Redis 정리 예외 발생 (Counter)

## 대안 비교

| 방식 | 시나리오 1 | 시나리오 2 | 프로세스 크래시 회복 | 관측과 재시도 |
|------|-----------|-----------|-------------------|-------------|
| 현재 (`@Transactional` 안에 정리) | 미커버 | 미커버 (롤백 위험) | 불가 | 없음 |
| `AFTER_COMMIT` 이벤트만 | 미커버 | 커버 | 불가 (이벤트 유실) | 리스너 로그 수준 |
| PaymentAttempt + Outbox | 커버 | 커버 | 가능 | 지표와 재시도 이력 |

## 도입 순서 제안

1. 승인 흐름에 `payment_attempt`와 Outbox 도입 (이슈 #257)
2. `OutboxWorker`, `PaymentRecoveryWorker` 신설
3. 취소 흐름에 대칭 적용 (신규 이슈)
4. 기존 `try/catch` 무음 처리 및 리스너 후보 코드 정리
5. 관측 지표와 알람 추가

## 향후 확장 검토

이 설계는 다음 확장 지점을 열어두고 있습니다. 각 항목은 필요 시점에 별도 이슈로 다룹니다.

### 클라이언트 중복 요청 방지 (Idempotency-Key 헤더)

`payment_attempt`는 서버 재시도의 멱등성을 다루지만, 클라이언트가 결제 버튼을 두 번 누르는 경우는 별도로 막아야 합니다. 결제 API 진입 지점에서 `Idempotency-Key` 헤더를 받아 Redis에 캐시하고 같은 키의 재요청은 저장된 응답을 반환하는 방식으로 처리합니다.

### Toss 웹훅 리스너 (대사 시스템의 세 번째 층)

Toss는 승인과 취소 결과를 API 응답과 웹훅으로 이중 통지합니다. 현재 설계는 API 응답과 `PaymentRecoveryWorker` 폴링에만 의존하지만, 웹훅을 세 번째 대사 채널로 추가하면 응답 유실 시 회복 지연을 크게 줄일 수 있습니다. 정합성 자체는 폴링만으로도 확보되지만 실시간성이 부족합니다. 웹훅 서명 검증과 중복 처리 정책이 별도로 필요합니다.

### 결제 완료 알림 fan-out (Redis Streams 기반)

알림 채널이 여러 개로 확장되면 `outbox` 발행 채널을 Redis Streams로 확장합니다. Outbox INSERT는 그대로 두고, Publisher가 로컬 소비자와 Redis Streams topic으로 함께 발행하는 형태입니다. 채널이 하나뿐이면 도입하지 않습니다. 라일로가 이미 Redis를 쓰고 있어 추가 인프라 부담이 낮습니다.

### 출발 전 알림 (시간 기반 발송)

"출발 30분 전"처럼 예약 시점에 미래로 잡아두는 발송은 이벤트 스트림과 성격이 다릅니다. 스케줄 outbox 테이블 또는 Redis ZSET(score = 발송 시각) 기반 Worker로 별도 처리합니다.

### MSA 분리 시점의 브로커 재검토

Auth와 Payment 등이 별도 서비스로 분리되면 서비스 경계를 넘는 이벤트가 필요해집니다. 그 시점에 Kafka(자체 호스팅 또는 OCI Streaming) 도입을 재검토합니다. Outbox → Kafka Publisher 패턴으로 확장하면 기존 코드 구조 변경이 최소화됩니다.

## 범위 밖

- 결제 승인과 취소 자체의 비즈니스 규칙 (환불 조건, 금액 계산 등)
- 좌석 충돌 검증 4-Layer 방어 (`docs/seat-conflict-validation.md`)
- 예약(PendingBooking) 만료 처리 방식 개편은 별도 로드맵에서 다룹니다
