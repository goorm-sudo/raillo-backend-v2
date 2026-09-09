# Redis 키와 보존 전략

> 구현 예정 설계다. 키 이름은 새 구조의 제안 계약이며 기존 TTL Hold 키와 호환된다고 가정하지 않는다.

[문서 목록](./README.md) · [로드맵](./roadmap.md) · [다음: Lua 스크립트](./13-lua-scripts.md)

## 키 수와 저장 단위

**운행 일정당 핵심 상태 키 5개 + 기준정보 키 4개 = 9종류**다. 예약 생성 요청이 변경하는 키는 핵심 상태 키 중 4개다. 아직 항목이 없는 키는 물리적으로 존재하지 않을 수 있다.

예약마다 키 9개를 만들지 않는다. 같은 운행 일정의 예약들은 같은 HASH·ZSET 내부에 항목을 추가한다. Redis Stream `events`는 사용하지 않는다.

```text
공통 접두사: rail:{schedule:1785}:
hash tag:    {schedule:1785}
```

같은 hash tag는 Redis Cluster의 동일 slot 배치를 위한 규칙이다. Lua가 접근할 키는 전부 명시적으로 `KEYS`에 전달한다. 다른 운행 일정의 예약을 단일 Lua로 묶지 않는다. [Redis Cluster](https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/)

## 핵심 상태 키 5개

| suffix | 타입 | 항목 | 주된 쓰기 시점 |
|---|---|---|---|
| `occupancy` | HASH | `seatId:section → reservationId` | 생성·취소·만료·예매 취소 |
| `reservations` | HASH | `reservationId → 예약 스냅샷` | 생성·주문 연결·상태 전이 |
| `deadlines` | ZSET | member=`reservationId`, score=`expiresAt` | 생성·취소·만료·승인 진입 |
| `idempotency` | HASH | `memberNo:requestKey → 요청 해시·예약 ID` | 예약 생성 |
| `confirmation-deadlines` | ZSET | member=`reservationId`, score=`nextCheckAt` | 승인 진입·복구·확정·실패 |

`reservations`는 도메인 저장소, `occupancy/deadlines/idempotency`는 이를 보조하는 Redis 저장 구조다. `SeatReservation`은 예약 본문의 값 객체이며 독립 키를 만들지 않는다.

## 생성 예시

```text
서울(0) → 대전(1) → 동대구(2) → 부산(3)
res_A: 좌석 12·13, 서울 → 동대구

occupancy
  12:0 → res_A
  12:1 → res_A
  13:0 → res_A
  13:1 → res_A

reservations
  res_A → {
    id, memberNo, trainScheduleId,
    departureStopId, arrivalStopId,
    departureStopOrder: 0, arrivalStopOrder: 2,
    seatReservations: [{seatId, passengerType, fare}],
    totalFare, fareVersion,
    status: HELD, version: 1, generation,
    createdAt, expiresAt,
    orderId: null, confirmationAttemptId: null
  }

deadlines
  res_A → 10:10의 Unix timestamp(ms)

idempotency
  member_A:request_001 → {requestHash, reservationId: res_A, retainedUntil}
```

`retainedUntil`은 제안 메타데이터다. 보존 기간은 미정이며 만료 정리 인덱스와 별도로 관리한다. 금액 직렬화는 정밀도를 잃지 않는 문자열 또는 합의한 최소 화폐 단위로 정하고 Java `BigDecimal`과 왕복 검증한다.

## 구간과 상태 판정

구간은 `[departureStopOrder, arrivalStopOrder)`에 포함된 인접 구간 ordinal이다. 서울→동대구는 `0, 1`이며 동대구→부산은 `2`다.

| 점유 소유 예약의 상태 | 예약 요청의 판정 |
|---|---|
| `HELD`이고 만료 전 | 충돌 |
| `HELD`이고 만료 후 | 재사용 가능 |
| `CONFIRMING` | 충돌, 일반 만료 대상 아님 |
| `CONFIRMED` | 확정 예매 점유이므로 충돌 |
| `CANCELLED/EXPIRED` | 재사용 가능, 오래된 필드는 소유자 확인 후 정리 |
| 본문 없음·손상·세대 불일치 | 빈 좌석으로 취급하지 않고 차단·복구 |

확정 예매를 복구 적재할 때도 점유 소유자와 대응하는 확정 예약 스냅샷을 함께 만든다. 과거 예매에 예약 ID가 없다면 복구용 고유 식별자 규칙을 정의해야 한다. 원본 Booking 참조를 보존하고 이후 부분 취소가 같은 소유자를 찾아 해제할 수 있어야 한다.

## TTL 대신 논리 만료를 선택한 이유

`expiresAt`은 유효성, `deadlines`는 만료 후보 검색, Worker는 물리 정리를 담당한다. ZSET은 자동 삭제 기능이 아니다.

Redis 7.4는 `HEXPIRE`로 HASH 필드별 만료를 지원한다. 따라서 HASH에 개별 TTL을 줄 수 없어서 ZSET을 택한 것은 아니다. [Redis HEXPIRE](https://redis.io/docs/latest/commands/hexpire/)

이 설계는 예약 본문이 먼저 사라지는 것을 막고, 점유·상태·인덱스를 함께 정리하며, 결제 중인 `CONFIRMING`을 결과 확인까지 보존하기 위해 논리 만료를 선택한다. `occupancy`와 예약 본문에 활성 수명 동안 자동 TTL·필드 만료를 설정하지 않는다.

`CONFIRMED`도 임시 예약 기한으로 삭제하지 않는다. 해당 운행의 판매·부분 취소·복구에 필요한 기간까지 보존하고, 운행 전체 키 삭제는 판매 종료와 진행 중 결제·미처리 Outbox·보존 요건을 확인한 뒤 수행한다.

## 기준정보 키 4개

| suffix | 타입 | 내용 |
|---|---|---|
| `meta` | HASH | `status`, `version`, `generation`, 판매 정책 정보 |
| `stops` | HASH | 정차역 ID·연속 순서·승차역 출발 시각 |
| `seats` | HASH | 좌석 ID·객차 ID·좌석 등급 |
| `fares` | HASH | 구간·등급별 운임 기준 |

`meta.status`는 `LOADING/READY/RECOVERING/CLOSED`다. 신규 예약은 `READY`에서만 허용한다. 기준정보 적재·갱신은 버전과 함께 일관되게 게시하며 중간 상태의 예약을 차단한다.

버전은 기준정보 변경을, `generation`은 점유를 포함한 재구축 세대를 식별한다. 복구 세대와 다른 오래된 작업을 단순 적용하지 않고 DB 상태와 재대조한다.

## 추가 인덱스와 메모리 정리

다음은 위 9개 계산에 포함되지 않으며 정확한 스키마는 구현 전 결정 사항이다.

| 추가 구조 | 필요 이유 | 결정할 사항 |
|---|---|---|
| 회원별 예약 참조 | 회원 목록 조회 | 갱신 실패 보정·일관성·보존 기간 |
| 활성 운행 일정 목록 | Worker가 대상 키 탐색 | 등록·제거·누락 복구·담당 분할 |
| 종료 데이터 보존 인덱스 | 끝난 예약·멱등성 기록 정리 | ZSET 추가 또는 제한된 HSCAN·보정 |

다른 slot의 인덱스를 예약 Lua와 원자 갱신한다고 가정하지 않는다. 예약 생성 이벤트도 없으므로 동기 후속 갱신 실패에 대한 대조·복구를 설계해야 한다.

`HSCAN`으로 정리할 경우 한 번에 모든 항목을 처리하지 않고 커서와 배치를 사용한다. 멱등성 보장 기간 이전에 기록을 제거하지 않으며, 점유가 참조하는 본문을 먼저 지우지 않는다. 본문을 제거한 뒤에도 요청 재생을 보장해야 한다면 종료 결과를 멱등성 기록에 보존하는 계약이 필요하다.

## 수용 기준

- [ ] 같은 운행 일정의 Lua 키가 동일 slot에 위치한다.
- [ ] 예약 생성이 핵심 키 4개만 변경하고 이벤트 키를 만들지 않는다.
- [ ] 현재 시각·score·`expiresAt` 단위가 UTC ms로 일치한다.
- [ ] 승인 성공 후 확정 점유와 본문이 보존된다.
- [ ] 보존 데이터가 무한 누적되지 않도록 정리 방식·보장 기간을 결정한다.
- [ ] 키·필드 타입 불일치, 본문 누락, 세대 불일치를 복구 대상으로 처리한다.
- [ ] 기존 확정 예매 복구 후 취소가 올바른 점유 소유자를 찾는다.

관련 문서: [예약 생성](./04-reservation-create.md), [예약 조회](./05-reservation-query.md), [Worker](./14-workers.md).
