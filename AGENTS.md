# AGENTS.md

This file provides guidance to coding agents (Claude Code, Codex 등) when working with code in this repository. `CLAUDE.md`는 이 파일에 대한 심볼릭 링크다.

## Build & Test

```bash
./gradlew build                                              # 전체 모듈 빌드 (raillo-common + raillo-core)
./gradlew test                                               # 전체 테스트
./gradlew :raillo-core:test --tests "com.sudo.raillo.booking.BookingServiceTest"  # 단일 클래스
./gradlew :raillo-core:test --tests "...BookingServiceTest.method_name"   # 단일 메서드
./gradlew clean build                                        # 클린 리빌드
./gradlew :raillo-core:bootRun                               # 앱 실행 (MySQL 필요, Redis는 compose로 자동 기동)
```

## Architecture

Spring Boot 4.1 + Java 25 + DDD. Gradle multi-module 구조.

### 모듈 구조

- **`raillo-common`** — 여러 서비스가 공유하는 순수 라이브러리 모듈. Spring Boot 실행 플러그인 미적용, jar만 배포. `com.sudo.raillo.common.*` 패키지.
- **`raillo-core`** — 실행 앱 모듈. 기존 모놀리스 도메인 전체가 여기 담김. `com.sudo.raillo.*` (common 제외) 패키지.

MSA 전환 로드맵 상 현재는 `raillo-core`가 모놀리스 전체지만, 이후 Auth·Payment 등이 별도 서비스 모듈로 분리될 예정. 자세한 전환 계획은 `docs/msa-transition/`.

### 도메인 (raillo-core 내부)

- `auth` — JWT 인증, 이메일 인증, 토큰 관리
- `booking` — Pending bookings, 좌석 예약, 승차권
- `member` — 사용자, 회원번호 생성, 만료 회원 Spring Batch
- `payment` — Toss Payments, 환불
- `train` — 열차 스케줄, 역, 운임, 좌석 가용성
- `order` — 결제 단위로 PendingBooking들을 묶음
- `global` — 실행 앱 인프라 (config, Redis 유틸 등)

### Package Structure

```
raillo/
├── raillo-common/                # 공유 라이브러리
│   └── src/main/java/com/sudo/raillo/common/
│       ├── domain/               # BaseEntity, YesNo
│       ├── exception/            # ErrorCode, BusinessException, DomainException, ExternalApiException, GlobalError, CommonExceptionHandler
│       └── response/             # SuccessCode, SuccessResponse, ErrorResponse, GlobalResponseHandler
└── raillo-core/                  # 실행 앱
    └── src/main/java/com/sudo/raillo/
        └── {domain}/
            ├── presentation/     # REST controllers
            ├── application/
            │   ├── service/      # Business logic
            │   ├── facade/       # Coordinates multiple services
            │   ├── dto/{request,response,projection}/
            │   ├── mapper/       # DTO ↔ Domain
            │   ├── validator/    # Business rule validation
            │   ├── calculator/   # Fares, refunds
            │   └── generator/    # Code generators
            ├── domain/           # Entities, enums (BaseEntity 상속)
            ├── infrastructure/   # Repositories (JPA, QueryDSL, Redis), 도메인별 config
            ├── exception/        # Domain-specific error codes (raillo-common의 ErrorCode 구현)
            ├── success/          # Domain-specific success codes
            └── docs/             # Swagger documentation interfaces
```

### Layer Rules

```
Controller → Facade → Service → Repository
```

- **Facade → Service only** (Facade → Facade 금지; 등장 시 Event-driven 검토)
- **Service → Repository** (own or cross-domain 모두 허용)
- **Service → Service 호출 금지**

## Key Patterns

**Exception Handling** — 3종 예외 계층은 `raillo-common/exception/`에 있다:
- `BusinessException` — Service/Application 레이어의 비즈니스 로직 오류 (검증 실패, 리소스 없음)
- `DomainException` — Entity/VO 내부 도메인 불변식 위반 (상태 전이 오류, VO 검증)
- `ExternalApiException` — 외부 API 호출 실패 (Toss Payments 등)

도메인별 에러 enum은 raillo-common의 `ErrorCode`를 구현한다 (위치는 각 도메인의 `exception/`):
```java
public enum BookingError implements ErrorCode {
    BOOKING_NOT_FOUND("예매 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "BOOKING_101");
}
```

> 코드 형식·접두사·밴드·추가 절차 → [docs/error-code-convention.md](./docs/error-code-convention.md)

**Exception Handler** — 3개로 분리:
- `CommonExceptionHandler` (raillo-common) — `BusinessException`, `ExternalApiException`, validation 예외 등 도메인 무관 처리. `@Order(LOWEST_PRECEDENCE)`
- `AuthExceptionHandler` (raillo-core auth) — `BadCredentialsException` 등 auth 도메인 예외. `@Order(HIGHEST_PRECEDENCE)`
- `RedisExceptionHandler` (raillo-core global/redis) — Redis 계열 예외. `@Order(HIGHEST_PRECEDENCE)`

**Validator Pattern** — `@Component`로 `application/validator/`에 `{Domain}Validator` 작성. Service/Facade에 주입되어 실패 시 `BusinessException`을 던진다.

**Response Handling** — 도메인별 `SuccessCode` enum 구현 (raillo-common의 `SuccessCode` 인터페이스). raillo-common의 `GlobalResponseHandler`가 void가 아닌 응답을 자동 래핑한다.

**Entity Base Class** — raillo-common의 `BaseEntity` 상속 → `createdAt`/`updatedAt` 자동 (`@MappedSuperclass`).

**Repository Pattern** — 기본 CRUD는 `JpaRepository`. 복잡한 쿼리는 `*QueryRepository` + `JPAQueryFactory` (QueryDSL projection). 둘 다 `{domain}/infrastructure/` 직속에 둔다 (별도 `repository/` 하위 디렉터리 두지 않음).

> **헥사고날 전환 도메인 예외**: `application/required/{Domain}Repository` port가 있는 도메인(현재 `payment`)에서는 "Repository" 이름을 port에 예약한다. QueryDSL projection 구현체는 `*QueryDao`로 두고 `adapter/persistence/`에 둔다 (Repository ≠ 쿼리 실행자). Spring Data 인터페이스는 `*JpaRepository`로 유지.

**Redis Repository Pattern** — `RedisTemplate<String, Object>`, TTL은 `@Value`로 주입. 커서 순회는 `ScanOptions`.

**Redis Lua Scripts** — 좌석 동시 선점 충돌 방지. 스크립트는 `raillo-core/src/main/resources/scripts/`:
- `seat_hold.lua` / `seat_release.lua` / `get_hold_seats_count.lua`
- Lua 스크립트 Bean은 `booking/infrastructure/config/RedisScriptConfig`가 등록
- 상세 흐름, Hold Index, Lazy Cleanup, Train Search 통합 → [docs/seat-hold-architecture.md](./docs/seat-hold-architecture.md)
- 4-Layer 좌석 충돌 방어 → [docs/seat-conflict-validation.md](./docs/seat-conflict-validation.md)

## Coding Rules

**Parameter Passing**:
- ≤3 parameters: 개별 전달 (Service 재사용성 ↑)
- ≥4 parameters: Request 객체로 묶기

**Transactions**:
- Service 클래스: `@Transactional` 클래스 레벨
- 읽기 전용 메서드: `@Transactional(readOnly = true)`
- 조회 전용 Service: 클래스 레벨 `@Transactional(readOnly = true)`

## Domain Model

전체 엔티티 목록, 관계도, Booking Flow, 한국어 도메인 용어 → [docs/domain-model.md](./docs/domain-model.md)

### Status Enums (요약)

| Entity | Status | Values |
|--------|--------|--------|
| Booking | `BookingStatus` | BOOKED, CANCELLED |
| Order | `OrderStatus` | PENDING, ORDERED, EXPIRED |
| Payment | `PaymentStatus` | PENDING, PAID, CANCELLED, REFUNDED, FAILED |
| Ticket | `TicketStatus` | ISSUED, USED, CANCELLED |
| TrainSchedule | `OperationStatus` | ACTIVE, DELAYED, CANCELLED |

### Other Enums

- `PassengerType` — ADULT (0%), CHILD (40% off), INFANT (75% off), SENIOR (30% off), DISABLED_HEAVY (50% off), DISABLED_LIGHT (30% off), VETERAN (50% off)
- `CarType` — STANDARD (일반실), FIRST_CLASS (특실)
- `SeatType` — WINDOW (창측), AISLE (복도측)

### Korean Terminology

예약 = PendingBooking, 예매 = Booking, 승차권 = Ticket, 객차 = TrainCar, 정차역 = ScheduleStop

## Testing

테스트 작성/수정은 **`/test` skill**을 사용한다. 컨벤션과 워크플로우 전체가 skill에 포함되어 있다.

- 환경: Testcontainers **MySQL 8.4.10 + Redis 7.4** (운영과 동일 버전, 실행에 Docker 필요), 통합 테스트는 `@ServiceTest`
- 컨테이너는 `TestContainerInitializer`가 JVM당 한 번 기동한다.
- `@SpringBootTest`를 직접 쓰는 테스트는 `@ContextConfiguration(initializers = TestContainerInitializer.class)`를 함께 붙여야 한다
- ⚠️ **테스트 메서드 `@Transactional` 절대 금지** — `@ServiceTest`의 cleanup(`DatabaseCleanupExtension`, `RedisCleanupExtension`)을 우회한다
- 상세 컨벤션 → `.agents/skills/test/SKILL.md` / Helper 빌더 예제 → [docs/testing-guide.md](./docs/testing-guide.md)

## Technology Stack

Java 25, Spring Boot 4.1.0, MySQL, Redis, Testcontainers, QueryDSL 5.1.0, JWT, Spring Batch.

## Git Workflow

- Main branch: `develop`
- CI runs on push/PR to `develop` via GitHub Actions
- ArgoCD syncs from `main` to production
- Performance testing: K6

**이슈/브랜치/커밋/PR 흐름** — 모두 프로젝트 skill로 자동화한다:
1. `/issue <작업 내용>` — 팀 GitHub Issue 컨벤션에 맞춘 제목·본문·라벨 생성
2. `/branch <이슈 설명>` — 팀 네이밍 컨벤션에 맞춘 브랜치명 생성
3. `/commit` — 브랜치명에서 이슈번호 파싱 + 변경사항 분석해 커밋 메시지 작성
4. `/pr` — 변경사항 분석 + 이슈 본문 기반 PR 생성

배포 환경, K8s/ArgoCD/Docker 상세 → [docs/deployment.md](./docs/deployment.md)

## Situational References

작업 맥락에 따라 다음 문서를 참조한다.

- **테스트 작성/수정 시** → **`/test` skill 호출**. 자동 호출 안 됐다면 명시적으로 `/test <대상>` 실행. 상세 예제는 [docs/testing-guide.md](./docs/testing-guide.md).

- **Lua 스크립트 / 좌석 동시성 작업 시** → [docs/seat-hold-architecture.md](./docs/seat-hold-architecture.md)
  핵심: 원자성 보장, Lazy Cleanup 패턴, Hold Index 3종 키 동시 갱신 (`hold:pendingId`, `holds`, `holding-seats`), `RedisScriptConfig` Bean 등록.

- **좌석 충돌 검증 로직 변경 시** → [docs/seat-conflict-validation.md](./docs/seat-conflict-validation.md)
  핵심: 4-Layer 방어 (Lua → SQL Fail Fast → SQL Re-validation → TTL Expiry) 영향 범위 모두 검토.

- **에러 코드 추가/변경 시** → [docs/error-code-convention.md](./docs/error-code-convention.md)
  핵심: `{DOMAIN}_{NNN}` 형식, 도메인별 카테고리 밴드(백의 자리), 새 코드 추가 절차.

- **새 도메인/엔티티 추가, Booking Flow 이해 시** → [docs/domain-model.md](./docs/domain-model.md)
  핵심: 엔티티 관계도, Status enum, 한국어 용어(예약/예매/승차권) 일관 사용.

- **배포/인프라/K8s 작업 시** → [docs/deployment.md](./docs/deployment.md)
  핵심: K8s 매니페스트는 `k8s/k8s-application`·`k8s-argocd`·`k8s-monitoring`, ArgoCD는 `main` 브랜치 sync, 환경변수는 `raillo-config`(ConfigMap)·`raillo-secrets`(Secret)를 `envFrom`으로 주입.
