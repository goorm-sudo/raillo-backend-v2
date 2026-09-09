# 구현 로드맵

[문서 목록](README.md) · [설계 기준·미결정 사항](01-decisions.md) · [검증·전환](17-validation-cutover.md)

## 진행 원칙

기존 booking 애플리케이션·저장소를 재활용하는 것을 전제로 하지 않는다. 남아 있는 도메인 모델과 order/payment/train 연동 지점을 기준으로 새 흐름을 구현한다. 현재 작업 트리의 사용자 삭제·수정은 유지한다.

아래 단계는 이슈 단위로 나눌 수 있다. 체크박스는 구현·검증 후에만 완료한다. 단계별 PR을 분리하더라도 삭제된 컴포넌트를 참조하는 코드가 있는 상태를 곧바로 운영에 배포하지 않는다.

```text
0 설계 확정 → 1 도메인 → 2 Redis 기반 → 3 예약 생성
                                      ↓
                     4 조회 → 5 취소·만료
                                      ↓
6 DB 진행 기록·Outbox → 7 결제 준비 → 8 승인 → 9 예매 확정
                                                ↓
                                  10 실패·복구 → 11 예매 취소
                                                ↓
                                  12 통합 검증·판매 전환
```

6단계의 순수 DB 모델·Outbox 기반은 예약 기능과 병행할 수 있다. 실제 상태 전이 계약은 0~1단계를 공유해야 한다.

## 0. 범위·정합성 규칙 확정

- [ ] [설계 기준](01-decisions.md)의 상태 전이와 책임 승인.
- [ ] D1~D3: 운임 스냅샷·할인/반올림·좌석 한도·유효기간·멱등성 정책 결정.
- [ ] D6~D7: 준비 의도 저장 위치·승인 시도·외부 호출 제어 계약 결정.
- [ ] D9: 성공한 임시 예약의 허용 유실량과 failover 시 판매 차단 정책 결정.
- [ ] D11~D12: 단일/다중 예약 출시 범위와 즉시 결제수단 범위 결정.
- [ ] HTTP·오류·진행 중 응답 등 공개 계약 초안 작성.

완료 기준: 예약 만료, CONFIRMING, 성공/실패 확정, 부분 취소의 상태·점유 책임에 모순이 없다. 숫자나 저장 위치를 추측해 구현하지 않는다.

## 1. 도메인 모델 정리

- [x] Reservation·SeatReservation·ReservationStatus 정의.
- [ ] expiresAt·정차 순서·운임 버전·주문·승인 시도·version/generation 추가.
- [ ] 좌석 목록 불변성·구간·금액·상태 불변식 구현.
- [ ] Booking·SeatBooking·Ticket에 주문 원본 식별·중복 생성 방지 연결 설계.
- [ ] PendingBooking 명칭이 남은 DTO·port·필드·문서 변경 목록 작성.

완료 기준: [DB·Redis 도메인](03-domain-model.md)의 책임과 직렬화 계약이 정의되고 도메인 검증이 통과한다.

## 2. Redis 기준정보·직렬화·키 기반

- [ ] 핵심 키 5개와 기준정보 4개의 KeyGenerator 구현.
- [ ] 같은 운행 일정의 모든 Lua 키에 같은 hash tag 적용.
- [x] TIME·UTC epoch ms·정확한 금액 표현·schemaVersion 결정.
- [ ] meta/stops/seats/fares 적재와 버전 일관성, READY 게시 구현.
- [ ] 운행별 Worker 등록·탐색 누락 보정 경로 구현.
- [ ] 확정 예매와 미완료 결제 점유 복원 기능의 입력 계약 정의.
- [ ] 직렬화 왕복·기준정보 누락·동일 slot 검증.

완료 기준: 미적재·불완전·복구 중 운행은 예약을 허용하지 않는다. 상세는 [키](12-redis-keys.md).

## 3. 예약 생성

- [x] 생성 Command·응답·소유자·중복 좌석·승객 검증.
- [x] Redis 기준정보를 입력받는 서버 운임 계산.
- [x] create_reservation.lua와 Repository·Service·Facade 구현.
- [x] 전체 구간 선검증 후 4개 핵심 키 저장.
- [x] 멱등성·만료 점유 재사용·부분 쓰기 오류 탐지 구현.
- [x] 실제 Redis의 동시 요청·중복 요청·구간 경합 검증.

2026-09-09: 준비된 기준정보를 사용하는 생성 경로를 구현·격리 검증했다. 기준정보 적재·복구, 전체 빌드 복구와 기존 연동은 미완료다. [구현 현황과 테스트 실행](18-reservation-create-implementation.md)

완료 기준: DB 없는 정상 생성 경로, 비즈니스 충돌 시 부분 점유 없음, 재시도에 새 예약이나 TTL 연장 없음. [예약 생성](04-reservation-create.md)

## 4. 예약·좌석 조회

- [ ] 상세 조회 권한·effectiveStatus·serverTime·expiresAt 구현.
- [ ] 회원 인덱스 D4 및 누락/오래된 항목의 보정 방식 결정·구현.
- [ ] 구간별 유효 점유 좌석 중복 제거와 잔여 좌석 계산.
- [ ] train 조회 연동에서 DB 예매 이중 차감 제거.
- [ ] 조회 직후 충돌 가능성을 API·화면 계약에 반영.

완료 기준: 만료 Worker 지연에도 정확한 유효 상태를 표시한다. 목록 누락을 예약 부재로 해석하지 않는다. [조회](05-reservation-query.md)

## 5. 취소·만료·종료 데이터 정리

- [ ] cancel_reservation.lua·expire_reservation.lua 구현.
- [ ] ReservationExpiryWorker의 bounded 후보 조회와 재검증.
- [ ] 새 소유자 보호·동시 Worker·승인 진입 경합 검증.
- [ ] D5 보존 정책과 ReservationCleanupWorker 구현.
- [ ] HELD 종료 후 연결된 주문 상태의 후속 정리 경로 마련.

완료 기준: Worker 종료/지연에도 만료 예약을 재사용하고 새 점유를 지우지 않는다. CONFIRMING은 일반 만료 대상이 아니다. [취소·만료](06-reservation-cancel-expire.md)

## 6. 주문·승인 시도·Outbox DB 기반

- [ ] 결제 준비 진행 기록의 저장 형태와 마이그레이션 확정.
- [ ] 주문 스냅샷에 reservationId·구간·운임·버전 반영.
- [ ] payment_attempt 및 다중 예약 진행 기록 구현.
- [ ] 주문·시도·원본 예매·승차권·Outbox 고유 제약 구현.
- [ ] Outbox Repository와 작업 확보·재시도·대상별 순서 기반 구현.
- [ ] DB 커밋 불명 시 안정적인 ID로 결과를 조회하는 계약 구현.

완료 기준: Redis·외부 호출 전에 영속 기록이 남고, 같은 업무 결과가 중복 생성되지 않는다. [도메인](03-domain-model.md), [Outbox](15-outbox.md)

## 7. 결제 준비

- [ ] 준비 멱등성·영속 준비 의도·bind/unbind Lua 구현.
- [ ] 원래 예약 유효기간을 유지하며 주문 연결.
- [ ] 스냅샷으로 주문 상세·Payment·준비 완료를 커밋.
- [ ] 일부 연결 성공·DB 실패·응답 유실·프로세스 종료 보상 검증.
- [ ] payment required port와 integration adapter 교체.

완료 기준: 같은 준비 요청은 같은 주문 결과, 다른 활성 주문에 중복 연결 없음. [결제 준비](07-payment-prepare.md)

## 8. 결제 승인 진입·외부 호출

- [ ] 승인 시도 중복 방지·금액 검증·paymentKey 선저장.
- [ ] begin_confirmation.lua, deadlines 이동·승인 시도 연결.
- [ ] 다중 예약이면 모든 전환 완료 후 외부 호출, 부분 실패 보상.
- [ ] PaymentGateway 승인·조회·취소 계약 확장.
- [ ] 외부 호출 전송 단계·늦은 실행자·provider 멱등성 계약 검증.
- [ ] 외부 HTTP 호출을 DB 트랜잭션 밖으로 분리.

완료 기준: 만료 예약은 승인하지 않고, 승인 중 좌석은 만료로 해제되지 않는다. [결제 승인](08-payment-confirm.md)

## 9. 결제 완료·예매 확정

- [ ] 결제 결과와 주문의 일치 검증.
- [ ] Payment·Order·Booking·SeatBooking·Ticket·Outbox 단일 커밋.
- [ ] confirm_reservation.lua로 CONFIRMED 전환, 점유 유지.
- [ ] 확정 Outbox와 즉시 반영 경로의 동일 멱등 계약.
- [ ] 승인 성공 후 DB 실패·DB 커밋 후 Redis 실패 검증.

완료 기준: 원본 주문에서 예매·승차권이 한 번만 생성되고 확정 좌석이 다시 예약되지 않는다. [예매 확정](09-booking-finalize.md)

## 10. 결제 실패·복구

- [ ] fail_confirmation.lua 및 실패·취소 사유 보존.
- [ ] PaymentRecoveryWorker: DB 미완료 준비·승인 기록과 Redis 기한 대조.
- [ ] 성공·실패·결과 불명·취소 보상별 복구 경로.
- [ ] OutboxWorker 중복·역순·부분 반영·세대 불일치 처리.
- [ ] 자동 처리 한도 초과 알림·운영 재처리·상태 조회.
- [ ] 모든 경계의 프로세스 종료·응답 유실 검증.

완료 기준: 결과 불명을 실패로 간주하지 않고, 자동 재시도가 끝나도 미해결 건을 완료로 표시하지 않는다. [실패·복구](10-payment-failure-recovery.md), [Worker](14-workers.md)

**8~10단계는 성공 경로만 먼저 실판매에 활성화하지 않는다. 승인·확정·복구가 함께 검증되어야 한다.**

## 11. 예매 취소·부분 취소

- [ ] 취소 요청·중복 방지·환불 정책·원본 좌석 연결 확정.
- [ ] 결제사 취소 조회·재시도와 DB 취소 확정.
- [ ] BookingCancelled Outbox·release_booking.lua 구현.
- [ ] 부분 취소 후 가용 좌석·승차권·복구 원장 일치 확인.
- [ ] 확정/취소 순서 역전 시 점유 부활 방지.

완료 기준: 취소 확인 전 해제 없음, 취소된 구간만 해제. [예매 취소](11-booking-cancel.md)

## 12. 통합 검증·판매 전환

- [ ] 실제 MySQL·Redis 기반 기능·동시성·장애 검증 완료.
- [ ] 예약 API DB 쿼리 수·지연·처리량·메모리·Worker backlog 측정.
- [ ] Redis 영속성·축출 방지·복구 세대·판매 차단·재개 절차 검증.
- [ ] 기존 확정 예매·진행 중 예약/결제·구 API 전환 정책 실행.
- [ ] DB 충돌 조회를 제거하기 전에 모든 확정 점유와 쓰기 경로 대조.
- [ ] 필요 없는 기존 Lua·키·설정·import·DTO·문서·테스트 참조 정리.
- [ ] 공개 API 문서·에러 코드·운영 문서 갱신, build/test 통과.

완료 기준: [전환 체크리스트](17-validation-cutover.md)를 통과하고 미결정 운영 정책이 남지 않는다. 새 시스템에서 예매된 좌석을 모르는 구 버전으로 단순 롤백하지 않는다.
