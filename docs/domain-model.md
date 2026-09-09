# Domain Model

## Core Entities by Domain

### Train Domain
- `Train` — Physical train with trainNumber, trainType (KTX, ITX), trainName
- `TrainCar` — Individual car with carNumber, carType (STANDARD/FIRST_CLASS)
- `Seat` — Individual seat with seatRow, seatColumn (A,B,C,D), seatType (WINDOW/AISLE)
- `Station` — Train station
- `TrainSchedule` — Train operation on specific date with operationStatus
- `ScheduleStop` — Stop information for a schedule (stopOrder, arrival/departure times)
- `StationFare` — Fare between two stations (standardFare, firstClassFare)

### Booking Domain
- `Reservation` — Temporary reservation in Redis (TTL 10min) before payment
- `SeatReservation` — Seat info within PendingBooking
- `Booking` — Confirmed booking after payment (예매)
- `SeatBooking` — Seat info within Booking
- `Ticket` — Issued ticket per seat after payment (승차권)

### Order Domain
- `Order` — Payment unit grouping multiple PendingBookings
- `OrderBooking` — Booking info converted from PendingBooking
- `OrderSeatBooking` — Seat info converted from PendingSeatBooking

### Payment Domain
- `Payment` — Toss Payments integration with paymentKey, paymentStatus

### Member Domain
- `Member` — User account with email, password, memberNo (format: `yyyyMMddCCCC`)

## Entity Relationships

```
Train → TrainCar (1:N) → Seat (1:N)
TrainSchedule → ScheduleStop (1:N) → Station (N:1)
Booking → SeatBooking (1:N) → Seat
Booking → Ticket (1:N)
Order → OrderBooking (1:N) → OrderSeatBooking (1:N)
Order → Payment (1:1)
Member → Booking (1:N)
Member → Order (1:N)
```

## Booking Flow

1. **열차 검색** — Query TrainSchedule + ScheduleStop
2. **좌석 선택** — Create PendingBooking + PendingSeatBooking (Redis, TTL 10min)
3. **결제 준비** — Convert to Order (PENDING) + OrderBooking + OrderSeatBooking, create Payment (PENDING)
4. **결제 승인** — Toss Payments approval → Payment (PAID), Order (ORDERED)
5. **예매 확정** — Convert to Booking + SeatBooking, issue Tickets, delete PendingBooking from Redis

## Domain Terminology (Korean)

- **예약** = PendingBooking (temporary, before payment)
- **예매** = Booking (confirmed, after payment)
- **승차권** = Ticket (issued document)
- **객차** = TrainCar
- **좌석** = Seat
- **정차역** = ScheduleStop

## Related Documents

- 좌석 충돌 검증 4계층 방어: [seat-conflict-validation.md](./seat-conflict-validation.md)
- Seat Hold Lua 아키텍처: [seat-hold-architecture.md](./seat-hold-architecture.md)
