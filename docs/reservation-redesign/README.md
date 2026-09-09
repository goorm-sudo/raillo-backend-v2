# 예약·예매 재설계

> 작성 기준: 2026-09-08. 이 폴더는 앞으로 구현할 설계와 작업 계획이다. 코드 구현 완료를 의미하지 않는다.

## 목표

예약 생성의 DB 조회 의존성을 제거하고, 하나의 운행 일정에 속한 좌석·구간을 Redis Lua로 검사·점유한다. 결제 준비부터 주문·결제 정보를 DB에 저장하고, 결제 완료 시 예매와 승차권을 확정한다.

사용자가 기존 booking 애플리케이션·저장소를 정리하고 새로 구현하기로 한 상황을 기준으로 작성했다. 기존 코드를 유지하며 부분적으로 수정하는 계획과 구분한다. 운영 데이터의 전환 문제는 코드 삭제 여부와 별개로 다룬다.

## 읽는 순서

| 문서 | 내용 |
|---|---|
| [구현 로드맵](roadmap.md) | 단계별 작업·의존성·완료 조건·배포 게이트 |
| [설계 기준과 미결정 사항](01-decisions.md) | 채택한 방향, 보완 제안, 구현 전 확정할 정책 |
| [전체 흐름](02-overall-flow.md) | Happy case, 실패 분기, 저장소·트랜잭션 경계 |
| [DB·Redis 도메인](03-domain-model.md) | Booking·SeatBooking·Ticket·Reservation·SeatReservation |
| [예약 생성](04-reservation-create.md) | 검증·운임 계산·원자적 선점·멱등성 |
| [예약·좌석 조회](05-reservation-query.md) | 상세·회원 목록·구간별 잔여 좌석 |
| [예약 취소·만료](06-reservation-cancel-expire.md) | ZSET·expiresAt·소유자 조건부 해제 |
| [결제 준비](07-payment-prepare.md) | 주문 연결·스냅샷·준비 실패 보상 |
| [결제 승인](08-payment-confirm.md) | CONFIRMING 전환·승인 시도·외부 호출 |
| [예매 확정](09-booking-finalize.md) | DB 확정·승차권·Redis 확정 점유 |
| [결제 실패·복구](10-payment-failure-recovery.md) | 결과 불명·서버 종료·보상·다중 예약 |
| [예매 취소](11-booking-cancel.md) | 전액·부분 취소와 점유 해제 |
| [Redis 키](12-redis-keys.md) | 핵심 키 5종·기준정보 4종·보조 인덱스 |
| [Lua 스크립트](13-lua-scripts.md) | 상태 전이별 입력·검증·읽기·쓰기 계약 |
| [Worker](14-workers.md) | 예약 만료·결제 복구·Outbox·데이터 정리 |
| [DB Outbox](15-outbox.md) | DB 결과를 Redis에 재반영하는 영속 작업 |
| [애플리케이션 구성](16-architecture.md) | Facade·Service·port·adapter·트랜잭션 |
| [검증·전환](17-validation-cutover.md) | 동시성·장애·성능·기존 데이터 전환 |

## 핵심 범위

- DB 예매 모델: `Booking`, `SeatBooking`, `Ticket`.
- Redis 예약 모델: `Reservation`과 그 안에 포함하는 `SeatReservation` 값 객체.
- `Reservation` DB 테이블, 예약 생성 Redis Stream, 예약 이벤트 Consumer는 기본 범위에서 제외한다.
- DB의 주문·결제·승인 시도와 Outbox는 결제 처리·복구를 위해 사용한다. Outbox는 Redis Stream과 별개의 DB 작업 기록이다.
- 예약 생성이 변경하는 키는 `occupancy`, `reservations`, `deadlines`, `idempotency` 4개다.
- `confirmation-deadlines`는 승인 직전에 추가로 사용한다. 기준정보·보조 인덱스와 개수를 구분한다.
- `HELD`의 유효성은 `expiresAt`으로 판단한다. ZSET은 Worker가 정리 대상을 찾는 인덱스다.
- `CONFIRMING`은 기한 초과만으로 해제하지 않는다. 결제 결과를 확인한다.
- 결제 완료 후 좌석 점유는 삭제하지 않고 확정 점유로 유지한다.

## 문서의 확정 수준

`구현 기준`은 대화에서 선택한 구조를 문서의 공통 기준으로 삼는다는 의미다. `보완 제안`은 실패 경계를 구체화하며 추가한 설계다. `미결정`은 해당 구현·배포 단계 전에 선택하고 문서를 갱신해야 하는 항목이다. 예시 API 경로, 클래스명, 배치 크기, 재시도 시간은 확정된 공개 계약이 아니다.

## 현재 코드와의 관계

- [Reservation](../../raillo-core/src/main/java/com/sudo/raillo/booking/domain/Reservation.java), [SeatReservation](../../raillo-core/src/main/java/com/sudo/raillo/booking/domain/SeatReservation.java)은 존재하지만 상태·만료·승인 시도 등 새 필드는 아직 없다.
- DB 예매 모델은 남아 있고, booking 서비스·저장소·컨트롤러는 정리된 상태다. 이름만 존재한다고 새 동작이 구현된 것으로 보지 않는다.
- payment는 `application/provided`, `application/required`, `adapter` 구조다. 이를 유지하며 예약 연동 계약을 새로 정의한다.
- [기존 Hold 문서](../seat-hold-architecture.md), [기존 충돌 검증 문서](../seat-conflict-validation.md), [기존 도메인 문서](../domain-model.md)는 이전 흐름을 포함한다. 새 설계의 상세 기준은 이 폴더이며, 기존 문서 갱신은 전환 단계 작업이다.

문서에 적힌 체크박스는 모두 앞으로 수행할 작업이다. 문서 작성만으로 체크하지 않는다.
