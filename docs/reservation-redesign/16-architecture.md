# 애플리케이션 구성과 트랜잭션 경계

[목록](README.md) · [도메인](03-domain-model.md) · [Worker](14-workers.md)

## 1. 현재 구조와 구현 방향

booking은 도메인 모델을 남기고 애플리케이션·인프라를 재작성한다. payment는 이미 provided/required port와 adapter가 있으므로 유지한다. 아래 구성요소 이름은 책임을 설명하는 제안이며 클래스 수를 맞추기 위해 그대로 생성할 필요는 없다.

프로젝트의 기본 규칙은 `Controller → Facade → Service → Repository`다. Facade→Facade와 Service→Service 호출을 새로 추가하지 않는다. 현재 코드의 위반 가능성이 새 설계의 예외를 자동 허용하는 것은 아니다.

## 2. booking 구성

| 구성요소 | 책임 | 주요 의존 대상 |
|---|---|---|
| ReservationController | 생성·조회·취소 입력과 응답 | ReservationFacade/조회 Service |
| ReservationFacade | 기준정보·운임·예약 저장 조율 | Service, validator/calculator |
| ReservationService | 명령 계약, Lua 결과를 비즈니스 결과로 변환 | ReservationRedisRepository |
| ReservationQueryService | 상세·회원별 예약 목록 | Redis 조회 저장소·보조 인덱스 |
| ReservationReferenceService | 기준정보 조회·적재·버전 | 기준정보 Redis 저장소, train Repository |
| SeatAvailabilityService | 구간별 유효 점유와 잔여 좌석 조회 | 좌석·예약 Redis 조회 저장소 |
| BookingService | DB 예매·좌석·승차권 생성/조회·취소 | Booking/SeatBooking/Ticket Repository |
| ReservationValidator | 입력·소유자 등 애플리케이션 검증 | 상태를 독자적으로 덮어쓰지 않음 |
| FareCalculator | BigDecimal 기반 계산 | 전달된 서버 기준 운임 |
| ReservationRedisRepository | 키·직렬화·스크립트 실행 | RedisTemplate·RedisScript |
| RedisScriptConfig | 스크립트 Bean 등록 | resources/scripts의 새 Lua |

Facade를 통해 Service들을 조율한다. 운임 Calculator가 DB StationFareRepository를 직접 조회하면 예약 생성의 DB 제거 목표가 깨지므로 계산에 필요한 기준값을 전달하는 순수 계산 경계를 마련한다.

인증된 memberNo는 서버 보안 컨텍스트에서 전달하고 클라이언트 소유자 필드를 신뢰하지 않는다. 세부 계약은 [예약 생성](04-reservation-create.md)을 따른다.

## 3. payment port·adapter

현재 `PaymentPreparer`, `PaymentConfirmer` 같은 provided 계약과 `PaymentGateway`, `PaymentRepository`, `OrderRegister` 등 required 계약을 검토해 교체·확장한다.

| required 책임 | 제안 계약 | 구현 |
|---|---|---|
| 예약 스냅샷 조회·주문 연결 | ReservationReader / ReservationOrderBinder | booking Redis 저장소를 사용하는 integration adapter |
| 승인 진입·성공·실패 전이 | ReservationConfirmation | 상태 전이 Lua를 사용하는 integration adapter |
| 주문 준비·조회 | OrderRegister / OrderReader | order 도메인·저장소를 사용하는 adapter |
| 예매 확정 | BookingCreator | booking 도메인·저장소를 사용하는 adapter |
| 외부 승인·조회·취소 | PaymentGateway | Toss adapter |
| 결제·시도·Outbox 영속화 | PaymentRepository 및 필요한 port | payment persistence adapter |

port 뒤에서 다른 application Service를 호출해 레이어 규칙을 우회하지 않는다. adapter는 필요한 도메인 동작·Repository를 사용하고, 공통 계산·매핑은 별도 컴포넌트로 추출한다. 중복 코드가 커지면 도메인 책임을 재조정한다.

기존 `PendingBookingReader`, `SeatHoldReleaser`, SQL 충돌 검사 port의 역할을 그대로 이름만 바꾸지 않는다. 새 계약은 원자적 상태 전이·결과 코드·복구 식별자를 명시해야 한다.

payment에서 Repository 명칭은 port에 예약한다. Spring Data는 `*JpaRepository`, QueryDSL 구현체는 `adapter/persistence/*QueryDao`를 따른다.

## 4. 조율과 DB 트랜잭션

외부 호출까지 감싸는 클래스 수준 DB 트랜잭션으로 결제 전체를 구현하지 않는다. 진행 조율을 Facade에 두고 DB 변경만 Service에 위임하거나, provided 진입점의 비트랜잭션 조율 역할과 짧은 트랜잭션 실행 port를 명시적으로 나눈다. 기존 PaymentConfirmService가 PaymentModifier Service를 호출하는 패턴은 그대로 복제하지 않는다.

| 단위 | 같은 트랜잭션에서 처리 |
|---|---|
| 준비 시작 | 멱등성·준비 operation·안정적인 주문 코드 |
| 준비 완료 | 주문 상세·Payment·준비 결과 |
| 승인 시도 시작 | 중복 검사·attempt 생성·paymentKey·처리 권한 |
| 승인 전송 기록 | 전송 가능 여부의 조건부 전이·대상 전환 완료 기록 |
| 예매 확정 | Payment·Order·Booking·SeatBooking·Ticket·Outbox |
| 실패 확정 | attempt 결과·Payment 실패·해제 Outbox |
| 예매 취소 확정 | 취소/환불 내역·승차권·활성 좌석 변경·해제 Outbox |

예매 확정은 단일 DB 트랜잭션 서비스가 여러 도메인 Repository/port를 이용하도록 한다. 현재 모놀리스의 같은 DB·트랜잭션 매니저를 전제로 하며, 향후 MSA 분리 시 동일 원자성을 유지한다고 가정하지 않는다.

DB 트랜잭션 커밋 완료 후 Redis를 갱신한다. 즉시 반영 실패는 Outbox가 복구한다. Redis Lua만 성공한 경우에도 DB가 롤백되면 Redis가 함께 되돌아간다고 가정하지 않는다.

## 5. 오류·DTO·직렬화

- 4개 이상의 입력은 command/request 객체로 묶는다. scheduleId·memberNo·attemptId 등을 장문의 위치 인자로 넘기지 않는다.
- DTO↔도메인↔Redis 저장 표현을 명시적으로 매핑한다. JSON 타입 정보에 Java 클래스명 전체를 영속 계약으로 박아 넣지 않도록 검토한다.
- 금액은 서버 BigDecimal로 계산하고 Redis에서는 정수 최소 화폐 단위 또는 정확한 decimal 문자열로 표현한다. Lua 부동소수 계산을 금액 확정에 사용하지 않는다.
- DomainException은 객체 불변식, BusinessException은 소유자·충돌·상태 검증, ExternalApiException은 결제사 오류에 사용한다.
- 결과 불명과 명확한 거부를 동일한 실패 코드로 합치지 않는다. HTTP 상태·공개 오류 코드는 구현 전 [설계 기준 D10](01-decisions.md)에서 확정한다.
- 재시도 허용 오류에서도 클라이언트가 새로운 멱등성 키를 자동 생성해 중복 작업을 만들지 않도록 계약한다.

## 6. 구현 확인 항목

- [ ] 예약 생성 정상 경로에서 train/seat/fare DB Repository 호출이 사라졌는지 확인.
- [ ] Service→Service, Facade→Facade 의존이 추가되지 않았는지 점검.
- [ ] DB 트랜잭션 내부 외부 결제 HTTP 호출이 없는지 확인.
- [ ] 결제 도메인의 provided/required 계약과 integration adapter 연결 확인.
- [ ] Worker와 API가 같은 상태 전이 계약을 사용하며 별도 직접 Redis 쓰기가 없는지 확인.
- [ ] 단일 DB 확정 트랜잭션에 Outbox가 포함되는지 확인.
