# 예매 확정

[목록](README.md) · [이전: 결제 승인](08-payment-confirm.md) · [다음: 실패·복구](10-payment-failure-recovery.md)

> 구현 예정 설계. 외부 승인 성공을 DB 예매로 확정하고, Redis에는 확정 점유를 유지한다.

## 1. 입력과 근거

주문·Payment·승인 시도·검증된 provider 성공 결과가 입력이다. 좌석·승객·운임은 결제 준비에서 영속화한 주문 스냅샷을 사용한다. 프런트가 보낸 좌석·가격으로 예매를 새로 구성하지 않는다.

같은 attempt가 이미 확정됐다면 기존 결과를 반환한다. 다른 attempt나 취소된 주문에 성공 결과를 무조건 적용하지 않고 실제 provider 결과와 DB 최종 상태를 대조한다.

## 2. 하나의 DB 트랜잭션

다음 변경은 현재 모놀리스의 동일 DB 트랜잭션에서 처리한다.

1. 승인 시도·주문·결제의 현재 상태와 처리 권한 확인.
2. Order를 ORDERED, Payment를 PAID로 변경하고 provider 결과 기록.
3. OrderBooking별 Booking 생성.
4. OrderSeatBooking별 SeatBooking·Ticket 생성.
5. 승인 시도의 예매 확정 진행 상태 기록.
6. Redis 확정 반영용 BookingConfirmed Outbox 저장.
7. 커밋 완료 확인.

DB 변경과 Outbox 중 하나만 커밋하지 않는다. 외부 HTTP 호출은 이 트랜잭션에 포함하지 않는다.

## 3. 예매·승차권 멱등성

```text
OrderBooking → Booking 1건
OrderSeatBooking → 확정 좌석·Ticket 각 1건
```

원본 주문 상세 식별자를 저장하고 고유 제약 또는 동등한 DB 보호를 둔다. 매 재시도마다 새로운 bookingCode·ticketNumber를 생성한 뒤 중복 INSERT를 기대하는 방식으로 구현하지 않는다.

현재 SeatBooking의 구간 인덱스는 중첩 구간 유일성을 보장하지 않는다. 같은 원본으로 인한 중복 생성 방지는 별도 제약이며, 다른 요청 사이 재고 정합성은 Redis 판매 정책과 복구 게이트가 담당한다. Redis 장애 후 무조건 DB INSERT 성공을 신뢰해 신규 판매를 재개하지 않는다.

## 4. Redis 확정

커밋 후 같은 Outbox 작업 ID로 `confirm_reservation.lua`를 실행한다. 즉시 반영에 실패하면 OutboxWorker가 재시도한다.

```text
검증:
  generation·orderId·attemptId·현재 상태·DB 전이 순서
  모든 확정 대상 구간의 점유 소유자

변경:
  Reservation CONFIRMING → CONFIRMED
  Booking 연결·적용된 DB 전이 순서 기록
  confirmation-deadlines에서 제거
  occupancy는 유지
```

동일 확정 작업의 재전달은 이미 반영된 결과를 확인한다. 예약 본문·점유가 없거나 다른 소유자라면 강제로 덮어쓰지 않고 재고 복구로 보낸다.

취소 작업이 선행 처리되었거나 더 최신 취소 결과가 있다면 오래된 확정 작업이 점유를 부활시키지 않도록 [Outbox 순서 계약](15-outbox.md)을 적용한다.

## 5. 장애와 사용자 응답

| 상태 | 처리 |
|---|---|
| provider 성공, DB 커밋 전 실패 | CONFIRMING 유지, DB 확정 재시도 또는 결제 취소 보상 |
| DB 커밋 결과 불명 | 동일 주문·attempt·원본 식별자로 DB 조회 후 판단 |
| DB 확정, Redis 호출 전 종료 | OutboxWorker가 Redis 확정 재시도 |
| Redis 확정, Outbox 완료 표시 전 종료 | 동일 작업 재전달·멱등 확인 |
| DB 확정, Redis 데이터 유실/불일치 | 판매 차단·재고 복구, 임의 점유 덮어쓰기 금지 |

결제·예매 완료 응답은 provider 성공과 DB 커밋을 근거로 한다. Redis 반영이 잠시 늦더라도 기존 CONFIRMING 점유가 보호되고 Outbox에 추적된다면 DB 예매 결과를 제공할 수 있다. 결제 성공만 확인되고 DB 예매가 미완료인 경우에는 처리 중과 조회 경로를 제공한다.

확정 후 사용자 예약 목록에서 감추더라도 Reservation 본문과 점유를 즉시 삭제하지 않는다. 새 예약 충돌 판정과 Outbox 재시도에 필요한 상태를 보존한다. 사용자 예매·승차권 조회는 DB를 사용한다.

## 6. 복구 가능한 원장

DB에서 운행·좌석·구간·원본 예약 ID·활성 취소 상태를 조회하여 Redis 확정 점유를 재구성할 수 있어야 한다. 기존 예매에 예약 ID가 없다면 이관 매핑을 만든다. Booking/SeatBooking의 삭제와 Ticket 보존 정책을 검토해 환불·부분 취소 이력이 복구에서 사라지지 않게 한다.

## 7. 수용 기준

- [ ] 동일 승인 결과를 여러 번 처리해도 예매·승차권·Outbox 중복이 없다.
- [ ] Payment·Order·예매·승차권·Outbox가 함께 커밋/롤백된다.
- [ ] DB 확정 후 점유가 사라지는 순간이 없다.
- [ ] DB 커밋·Redis 반영 사이 종료를 복구한다.
- [ ] 부분 취소 이후 오래된 확정 재전달이 점유를 복원하지 않는다.
- [ ] DB에서 확정 점유를 복원할 수 있다.
