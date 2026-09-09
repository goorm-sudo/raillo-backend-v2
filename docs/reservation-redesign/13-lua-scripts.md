# Lua 스크립트 계약

[목록](README.md) · [Redis 키](12-redis-keys.md) · [Worker](14-workers.md)

> 구현 예정 계약이다. 스크립트 이름은 제안이며 기존 seat_hold/seat_release 스크립트의 이름 변경만으로 구현하지 않는다.

## 1. 공통 실행 규칙

- 스크립트가 접근하는 실제 Redis 키를 모두 `KEYS`로 전달한다. 예약 ID에서 새로운 키를 동적으로 만들어 접근하지 않는다.
- 하나의 운행 일정에 속한 키는 같은 `{schedule:id}` hash tag를 사용한다. 다른 운행 일정은 별도 호출·보상으로 처리한다.
- `ARGV`에는 인증된 회원·작업 ID·요청 값·예상 상태·버전·세대 등을 전달한다. 인자 스키마와 제한을 고정한다.
- 시간상 판단은 Redis TIME으로 계산한 epoch ms를 사용한다. 클라이언트 시간으로 만료를 결정하지 않는다.
- 키 타입·본문·배열 크기·중복·금액 표현·모든 대상의 조건을 쓰기 전에 확인한다.
- 비즈니스 검증 실패는 예약 상태·점유 쓰기 전에 반환한다.
- Lua 실행은 다른 명령의 개입을 막지만 실행 중 오류의 이전 쓰기를 자동 롤백하지 않는다. operation ID·상태 대조·부분 쓰기 복구를 구현한다.
- 명령 수는 요청 좌석 수×구간 길이에 비례하도록 제한한다. 전체 HASH·운행 목록 순회를 한 스크립트에 넣지 않는다.
- 현재 상태·점유·세대 검사가 필수다. 자바 객체의 과거 상태만으로 변경하지 않는다.

근거: [Redis Lua 실행·KEYS 규칙](https://redis.io/docs/latest/develop/programmability/eval-intro/), [Redis Cluster hash tag](https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/).

## 2. 필요한 변경 스크립트

표의 키 이름은 공통 접두사 `rail:{schedule:id}:`를 생략한다. meta는 재고 세대·판매/복구 조건 검사에 사용하며 복구 중 확정 결과 처리 정책도 명시적으로 제한한다.

| 스크립트 | 주요 읽기 | 주요 쓰기 | 역할 |
|---|---|---|---|
| create_reservation.lua | meta/stops/seats/fares, reservations/occupancy/idempotency | reservations/occupancy/deadlines/idempotency | 여러 좌석 예약 생성 |
| bind_order.lua | meta, reservations, occupancy | reservations | HELD를 주문에 연결 |
| unbind_order.lua | meta, reservations | reservations | 실패한 준비의 연결 해제 |
| cancel_reservation.lua | meta, reservations, occupancy | reservations/occupancy/deadlines | 사용자 예약 취소 |
| expire_reservation.lua | meta, reservations, occupancy, deadlines | reservations/occupancy/deadlines | HELD 만료 정리 |
| begin_confirmation.lua | meta, reservations, occupancy | reservations/deadlines/confirmation-deadlines | 승인 진입 |
| confirm_reservation.lua | meta, reservations, occupancy | reservations/confirmation-deadlines | DB 확정 반영 |
| fail_confirmation.lua | meta, reservations, occupancy | reservations/occupancy/deadlines/confirmation-deadlines | 승인 실패/중단/취소 확정 후 정리 |
| release_booking.lua | meta, reservations, occupancy | reservations/occupancy | 확정 예매 취소 범위 해제 |

상태 전이·복구 보정에 필요한 추가 키 접근은 구현 계약에 함께 기록한다. 선언하지 않은 키를 암묵적으로 쓰지 않는다. 일부 표에서 쓰지 않는 occupancy도 소유권 검증에는 필요하다.

## 3. 생성 — create_reservation

입력: memberNo, 요청 키·hash, reservationId, 기준정보 버전·generation, 구간·좌석/승객·운임 스냅샷, 예약 시간 정책.

1. 멱등성 기존 결과·ID 충돌·입력 검증.
2. READY·기준정보·버전·세대·출발 시각 확인.
3. 전체 좌석·구간의 소유자와 Reservation 유효성 확인.
4. 모두 가능하면 HELD 본문, 모든 점유, deadlines, 멱등성 기록 저장.
5. 예약 ID·시간·상태 반환.

HELD 만료 점유는 재사용할 수 있다. CONFIRMING/CONFIRMED는 원래 expiresAt이 지났어도 충돌이다. 본문 누락은 빈 좌석이 아니라 데이터 오류다. [생성 상세](04-reservation-create.md)

## 4. 주문 연결 — bind/unbind

bind 입력: 예약 참조, memberNo, orderId, 준비 operation ID, 기대 세대·스냅샷 버전.

- HELD·만료 전·현재 전체 점유를 확인한 뒤 주문 연결.
- 같은 주문/준비 operation이면 연결 확인으로 응답, 다른 활성 주문이면 거부.
- expiresAt은 변경하지 않음.
- 연결 후의 스냅샷과 version을 반환해 DB 주문 생성에 사용.

unbind 입력: 동일 준비 operation의 예약·orderId·generation.

- 같은 주문이 연결된 HELD에서만 orderId를 해제.
- 같은 보상의 재시도는 이미 연결 해제된 결과를 확인.
- CONFIRMING·CONFIRMED·다른 주문을 변경하지 않음.
- 원래 예약 시간을 연장하거나 새 점유를 만들지 않음.
- DB 준비 결과가 불명확하면 상위 유스케이스가 먼저 조회해야 함.

## 5. HELD 종료 — cancel/expire

cancel은 인증된 memberNo를 확인하고, expire는 Worker 입력에 의존하지 않고 현재 Redis 시간으로 재판정한다.

```text
현재 HELD이고 now >= expiresAt → EXPIRED
현재 HELD이고 유효한 사용자 취소 → CANCELLED

각 occupancy 필드의 값 == 해당 reservationId인 경우만 HDEL
종료 상태·종료 시각·version 저장
deadlines에서 제거
```

새 예약이 만료 점유를 재사용했으면 그 필드는 유지한다. 이미 종료된 예약의 중복 처리에서도 남은 같은 소유자 점유를 안전하게 보정한다. CONFIRMING/CONFIRMED를 일반 취소/만료로 해제하지 않는다.

본문이 없는 후보를 정상 종료로 반환하거나 영구적으로 정상 후보 처리를 막도록 두지 않는다. 이상 후보 격리·복구 정책은 Worker 계약에서 결정한다. [취소·만료](06-reservation-cancel-expire.md)

## 6. 승인 진입 — begin_confirmation

입력: 예약 참조, memberNo, orderId, attemptId, generation, 결과 확인 기한 정책.

- 주문·소유자·HELD·now < expiresAt·전체 점유 확인.
- HELD → CONFIRMING, attemptId·version 기록.
- deadlines에서 제거하고 confirmation-deadlines에 확인 기한 등록.
- 동일 attempt의 CONFIRMING 재시도는 기존 결과 확인. 다른 attempt는 거부.
- 재시도로 확인 기한을 무제한 연장하지 않음.

CONFIRMING 이후 기한은 자동 해제 시간이 아니다. [승인](08-payment-confirm.md)

## 7. DB 결과 반영 — confirm/fail

공통 입력: Outbox ID, 대상 예약·orderId·attemptId·generation, DB 전이 sequence, 기대 상태, 검증된 최종 결과.

### confirm_reservation

- DB 커밋된 예매 확정 작업만 전달한다.
- 동일 시도의 CONFIRMING과 전체 대상 점유를 확인한다.
- CONFIRMED·Booking 연결·적용 sequence를 저장하고 confirmation-deadlines에서 제거한다.
- occupancy는 지우지 않는다. 다른 소유자의 점유를 덮어쓰지 않는다.
- 이미 취소된 더 최신 결과를 과거 확정으로 되돌리지 않는다.

### fail_confirmation

- 상위 계층에서 실패/안전한 승인 중단/취소 완료를 확정한 작업만 전달한다.
- 같은 orderId·attemptId의 CONFIRMING인지 확인하고 CANCELLED로 전환한다.
- 자신의 점유만 해제하고 confirmation-deadlines에서 제거한다. 같은 예약의 일반 deadlines 항목이 비정상적으로 남았다면 함께 보정한다.
- 중복 정리, 일부 대상 전환 후 보상, 결과 불명 상태를 구별한다.
- 이미 CONFIRMED이거나 다른 attempt면 해제하지 않고 대조 대상으로 반환한다.

Lua가 DB·결제사에 문의하거나 타임아웃을 승인 실패로 추정하지 않는다. [복구](10-payment-failure-recovery.md), [Outbox](15-outbox.md)

## 8. 확정 좌석 해제 — release_booking

입력: 취소 operation ID·Booking·Reservation·generation·DB sequence·취소된 좌석/구간.

원본 예매 범위와 현재 소유자를 검증한 뒤 취소 대상의 점유만 제거하고 적용된 취소 범위·순서를 기록한다. 부분 취소 시 나머지 점유·예약 본문은 유지한다. 오래된 확정이나 중복 취소가 재고를 부활시키거나 새 예약을 삭제하지 않아야 한다. [예매 취소](11-booking-cancel.md)

## 9. 결과 코드와 재시도

| 논리 결과 | 상위 계층 처리 |
|---|---|
| APPLIED | 이번 상태 변경 완료 |
| ALREADY_APPLIED | 동일 작업의 이전 완료를 확인 |
| SEAT_CONFLICT | 비즈니스 거부, 새로운 좌석 선택 |
| EXPIRED / INVALID_STATE / WRONG_OWNER | 요청/상태 오류로 처리 |
| VERSION_CHANGED | 기준정보 재조회 등 명시된 범위만 재시도 |
| GENERATION_MISMATCH / DATA_INCONSISTENCY | 대조·복구, 무조건 성공 또는 빈 재고 처리 금지 |
| RUNTIME_ERROR / 결과 유실 | 같은 operation으로 상태 대조, 부분 성공 가능성 확인 |

이름은 내부 계약 예시이며 공개 ErrorCode는 별도로 프로젝트 규칙에 맞춘다. 과거 작업이라는 이유만으로 항상 ALREADY_APPLIED를 반환하지 않고 실제 적용·대체된 결과를 확인한다.

## 10. 조회·정리용 보조 스크립트

상세 조회의 본문·TIME 동시 읽기, 가용 좌석 배치 조회, 종료 데이터 조건부 정리도 짧은 Lua로 구현할 수 있다. 예시명은 read_reservation.lua, get_seat_availability.lua, cleanup_reservation.lua다. 필수 변경 스크립트 9개와 별개이며 API·배치 방식이 정해진 뒤 확정한다.

cleanup은 종료 상태·보존 기한·관련 점유/멱등성 보존 조건을 확인한다. DB 복구 참조가 남았는지 확인하는 것은 상위 계층의 책임이다. CONFIRMING과 판매 중인 CONFIRMED를 지우지 않는다.

## 11. 등록·검증

- [ ] resources/scripts의 새 Lua와 RedisScriptConfig Bean을 등록한다.
- [ ] 인자·응답 스키마와 제한값을 Java 저장소와 일치시킨다.
- [ ] 실제 Redis에서 중복 호출·경합·오류 주입을 검증한다.
- [ ] 동일 slot·모든 KEYS 전달·NOSCRIPT 재적재 경로를 확인한다.
- [ ] 오류 발생 후 부분 쓰기를 정상 성공으로 오인하지 않는다.
- [ ] API·Worker·Outbox가 동일한 상태 변경 계약을 사용한다.
