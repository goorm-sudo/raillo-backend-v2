# 예약 생성 구현 현황

작성 기준: 2026-09-09. [로드맵](roadmap.md)의 **3단계 예약 생성**과 필요한 Redis 직렬화·도메인 필드를 구현했다. 기준정보를 미리 적재한 운행을 대상으로 한다. 전체 예약·결제 시스템의 전환 완료를 의미하지 않는다.

[문서 목록](README.md) · [예약 생성 설계](04-reservation-create.md)

## 구현한 요청 경로

```text
POST /api/v1/reservations
  → ReservationController: 인증 principal의 memberNo 사용
  → ReservationFacade
  → ReservationService: 요청 검증·정규화·운임 계산
  → ReservationRedisRepository
  → create_reservation.lua
```

신규 요청은 보통 같은 Lua를 두 번 실행한다.

1. 멱등성 기록을 확인한다. 기록이 없으면 Redis 기준정보를 원자적으로 읽어 `QUOTE`를 반환한다. 아직 좌석을 점유하지 않는다.
2. Java `BigDecimal`로 할인 운임을 계산한다. Lua가 기준정보 버전·세대를 재확인하고 전체 좌석을 검사한 뒤 생성한다.

중간에 다른 사용자가 좌석을 예약하면 두 번째 실행에서 충돌한다. 버전이 변경되면 최신 기준정보로 최대 3회 시도한다. 기존 요청의 재시도는 운임 기준정보를 읽기 전에 예약을 재생하므로 가격 변경으로 결과가 달라지지 않는다.

예약 생성 Service는 `@Transactional(propagation = NOT_SUPPORTED)`로 JPA 트랜잭션을 시작하지 않는다. 요청 경로에서 DB Repository를 호출하지 않는다.

## 요청과 응답

```http
POST /api/v1/reservations
Authorization: Bearer <access-token>
Idempotency-Key: reserve_20260909_001
Content-Type: application/json

{
  "trainScheduleId": 1785,
  "departureStationId": 1,
  "arrivalStationId": 3,
  "seats": [
    {"seatId": 11, "passengerType": "ADULT"},
    {"seatId": 12, "passengerType": "CHILD"}
  ]
}
```

HTTP 201의 `result`에 `reservationId`, `trainScheduleId`, `status`, `seats`, `totalFare`, `createdAt`, `expiresAt`을 반환한다. 좌석별 `carType`, 할인 후 `fare`도 포함한다. 시간은 UTC Unix timestamp(ms)다.

동일 회원·운행의 동일 키와 동일 요청은 같은 예약 ID와 기한을 반환한다. 좌석과 승객 유형의 쌍을 유지한 채 좌석 ID 순으로 정렬하므로 배열 순서 변경은 동일 요청이다. 내용이 달라지면 `BOOKING_308`로 거부한다. 만료된 예약의 재요청은 기한을 연장하지 않고 `EXPIRED`를 반환한다. 재생 응답도 HTTP 201이며 클라이언트는 `status`를 확인해야 한다.

회원 번호와 금액은 클라이언트에서 받지 않는다. 멱등성 키는 영문·숫자·`_`·`-` 조합의 1~128자다. 키가 없거나 형식이 잘못되면 400을 반환한다.

## 이번 구현에 적용한 정책

| 항목 | 구현값 |
|---|---|
| 한 요청의 범위 | 하나의 운행, 같은 승하차 구간, 같은 객차 등급 |
| 좌석 수 | 1~8석, 중복 좌석 거부 |
| 기본 유효기간 | `reservation.hold-duration: 10m` |
| 출발 전 마감 여유 | `reservation.sales-cutoff: 0s` |
| 실제 기한 | `min(Redis TIME + hold-duration, 승차역 departureAt - sales-cutoff)` |
| 운임 | 기존 승객 유형 할인율 유지, 추가 반올림 없음, 예약 시점 운임 보관 |
| Redis 구간 순서 | 0부터 시작하는 연속 ordinal, 최대 256개 구간 |
| 멱등성 scope | 회원 + 운행 + 요청 키 |
| 종료 데이터 보존 | 자동 제거 없음. 정리 Worker/보존 기간은 다음 단계 |

10분·마감 여유 0초는 구현 기본값이며 서비스 정책에 맞춰 설정을 변경할 수 있다. 최대 좌석 수는 요청 검증과 Lua 양쪽에 적용했다. 할인 자격 증빙과 결제사의 원 단위 금액 제약은 결제 연동 전에 확정해야 한다.

## Redis 기준정보 적재 계약

**자동 DB 적재기와 확정 점유 복구기는 아직 구현하지 않았다.** 예약 요청이 누락된 기준정보를 DB에서 가져오는 fallback도 없다. 기준정보 적재는 예약 요청과 별도로 수행해야 한다.

모든 키는 `rail:{schedule:1785}:` 접두사를 사용한다. Lua가 읽는 키와 필드는 다음과 같다. JSON의 ID는 Lua 숫자의 정밀도 손실을 막기 위해 **문자열**로 적재한다.

| 키 | HASH field | value 예시 |
|---|---|---|
| `meta` | `status` | `READY` |
| `meta` | `version` | `v1` |
| `meta` | `generation` | `g1` |
| `meta` | `inventoryReady` | `1` |
| `stops` | 역 ID `1` | `{"id":"101","ordinal":0,"departureAt":1789000000000}` |
| `stops` | 역 ID `3` | `{"id":"103","ordinal":2,"departureAt":1789001200000}` |
| `seats` | 좌석 ID `11` | `{"carType":"STANDARD","available":true}` |
| `fares` | `1:3:STANDARD` | `10000` |

`stops.id`는 DB의 **ScheduleStop ID**, HASH field는 **Station ID**다. 표의 시간은 형식 예시이며 실제 운행일·승차역 출발 시각을 UTC ms로 변환해야 한다. 자정을 넘기는 운행도 실제 날짜를 반영한다. DB stopOrder가 불연속이면 연속 ordinal로 정규화한다.

`version`과 `generation`은 영문·숫자·`_`·`-`의 1~80자 토큰이다. 금액은 지수 표기 없는 음이 아닌 소수 문자열이다. Java 타입 메타데이터가 붙는 기존 Redis Object serializer로 저장하면 안 된다.

기준정보 적재·변경 시 지켜야 하는 순서:

1. `status=LOADING` 또는 `RECOVERING`으로 판매를 차단한다.
2. 정차역·유효 좌석·운임을 빠짐없이 적재하고 검증한다.
3. DB 확정 예매와 진행 중 결제의 점유를 복원한다. 기존 판매 경로와의 동시 쓰기도 차단해야 한다.
4. 적재마다 재사용하지 않는 새 `version`을 발급한다. 재구축 시 `generation`도 새 값으로 발급한다.
5. 전체 인벤토리 대조가 끝난 뒤 `inventoryReady=1`과 `status=READY`를 게시한다.

`inventoryReady=1`은 복구 작업의 완료 확인값이다. 기준정보만 넣고 이 값을 임의로 설정하면 DB에서 이미 판매한 좌석을 다시 예약할 수 있다. 이 값 자체가 DB를 검증해 주지는 않는다. 모든 판매 경로가 같은 Redis 점유를 사용하도록 전환하기 전에는 실판매에 연결하지 않는다.

핵심 키·기준정보 키에는 TTL을 설정하지 않는다. 저장용 전용 `RedisTemplate<String, Object>`는 key/value/hash field를 모두 문자열로 직렬화하며 다른 도메인의 Redis Bean 설정을 바꾸지 않는다.

## 저장과 오류 처리

신규 예약은 `reservations`, `occupancy`, `deadlines`, `idempotency` 네 키만 생성하거나 수정한다. 이벤트·Stream·DB Reservation은 만들지 않는다.

- `HELD`는 `expiresAt` 경계에서 논리 만료된다. 만료 Worker가 없어도 해당 구간을 다른 요청이 재사용할 수 있다.
- `CONFIRMING/CONFIRMED`는 원래 기한이 지나도 충돌한다. 상태 전이 자체는 이후 결제 작업 범위다.
- 만료된 예약의 다른 구간은 임의로 지우지 않는다. 후속 Worker는 소유자 일치 조건으로 정리해야 한다.
- 멱등성 기록에 `completed=false`를 먼저 쓰고 모든 쓰기가 끝난 뒤 `true`로 변경한다. 중간 쓰기 오류는 재시도 시 정상 성공으로 재생하지 않는다.
- 활성 예약을 재생할 때 점유 소유자와 `deadlines`를 대조한다. 손상된 JSON·잘못된 키 타입·다른 세대의 점유·본문 누락도 차단한다.
- 연결 종료·타임아웃·파싱 실패는 `BOOKING_312` 결과 불명으로 응답한다. 자동으로 좌석을 해제하지 않으며 같은 멱등성 키로 결과를 확인해야 한다.

Lua 런타임 오류의 자동 롤백이나 Redis failover의 무손실을 보장하지 않는다. 이번에는 불완전 기록의 재생 차단을 구현했으며, 판매 차단·운영 알림·자동 복구·종료 데이터 정리는 별도 작업이다.

## 검증 방법과 남은 작업

현재 기본 `compileJava`는 기존 Booking 삭제 후 남은 참조 때문에 실패한다. `BookingRepository`, `BookingValidator`, `BookingService`, `PendingBookingService`, `SeatHoldService`, `BookingTimeFilterConverter`를 참조하는 구 결제·열차 조회·회원·설정 코드가 대상이다. 이번 작업에서 해당 코드를 임시 구현으로 되살리지는 않았다.

기본 `build/test`를 변경하거나 오류를 숨기지 않고, 다음 별도 source set으로 새 예약 생성 코드를 컴파일하고 검증한다.

```bash
./gradlew -I gradle/reservation-verification.init.gradle :raillo-core:reservationTest
```

2026-09-09 실행 결과 **42개 통과**: `ReservationServiceTest` 35개, `ReservationFareCalculatorTest` 7개. 실제 Redis 7.4와 MySQL Testcontainers를 사용한다. Service/HTTP 테스트는 별도 Spring 설정으로 신규 구성요소만 로드한다. HTTP 인증 테스트는 테스트용 Basic 인증을 사용하며 운영 JWT 필터 전체를 검증하는 테스트는 아니다.

검증 범위는 운임 정확성, HTTP 인증·검증·201 응답, DB 연결 미발생, 구간 충돌, 동시 요청, 멱등성, 큰 ID의 정밀도, 기한 경계, 상태 손상, 기준정보 버전 경합과 재시도 상한이다. 테스트가 성공해도 전체 애플리케이션의 기동·결제 가능 여부와는 구분한다.

다음 작업은 기준정보 적재·확정 점유 복구, 기존 코드의 끊어진 참조 정리, 예약 조회, 취소·만료·보존 Worker 순이다. 이후 결제 준비·승인·예매 확정과 연결한다. 전체 빌드 복구 후 별도 검증 source set을 제거하고 기본 테스트 경로로 통합한다.
