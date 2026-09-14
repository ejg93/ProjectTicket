# 계획

## 무엇을 만드나

**공연 예매 시스템.** 기획사가 공연과 회차를 올리고, 관객이 대기열을 지나 좌석을 골라 예매·결제하고, 취소하면 정책대로 환불된다.
목적은 티켓을 파는 것이 아니라 **오픈 순간의 동시성·대기열·정합성·비동기 분리**를 설계하고 측정하는 것이다.

## 확정 사항

사용자가 고른 것들이다. 근거는 ADR 0001.

| 항목 | 결정 |
|---|---|
| 스택 | Kotlin + Spring Boot 4 + JdbcClient + PostgreSQL + Redis, Next.js |
| 도메인 | 공연(콘서트·뮤지컬) 한 종류, 기획사 여럿, 좌석 등급+구역+지정석 |
| 예매 범위 | 좌석 지정, 대기열, 선점 만료(5분), 1인 N매 제한, 모의 결제, 취소·환불 수수료 |
| 비동기 | Spring 이벤트 + DB 아웃박스로 시작, 소비자 둘째에서 Kafka |
| 인프라 | Compose → `--scale app=3` → kind k8s + Helm. AWS 없음. Railway 는 선택 |
| 부하 목표 | k6, 동시 1만 접속·좌석 1천 경쟁 |
| 이식 | ADR 0002 표대로 |

## 도메인 한 장

상세는 `doc/reference/domain-model.md`(D2).

```
organizer(기획사) ─< event(공연) ─< performance(회차) ─< performance_seat(회차별 좌석 상태)
venue(공연장) ─< hall(홀) ─< seat(물리 좌석: section·row·number)   ↑ seat 를 회차 오픈 때 복제
event ─< seat_grade(등급·가격)                                   performance_seat.grade
account(관객) ─< reservation(예매) ─< reservation_seat ─> performance_seat
reservation ─ payment(모의) ─ refund
queue(Redis ZSET, 회차 단위) → 활성 토큰이 있어야 예매 API 를 부른다
```

**좌석 상태의 단일 진실은 `performance_seat.status` 다.** `AVAILABLE → HELD → RESERVED`, HELD 는 `held_until` 이 지나면 스윕이 되돌린다.
Redis 는 대기열과 캐시고, 좌석을 확정하지 않는다.

## 기준 문서

여러 청크가 같이 참조하는 결정이다. 조건(선행 완료 + 정할 근거가 코드에 있음)이 찬 것부터 코드 청크보다 먼저 쓴다.
이식 머리말이 붙은 문서는 `P` 줄 이식 청크가 닫아야 「완료」다.

| # | 문서 | 무엇을 정하나 | 상태 |
|---|---|---|---|
| D1 | `doc/README.md`·`glossary.md` | 문서 트리, 도메인 용어 한↔영 | 완료 |
| D2 | `domain-model.md` | 엔티티 관계·경계·수명 | 완료(초안) |
| D3 | `state-machines.md` | 예매·결제·환불·회차·대기열 토큰의 상태와 전이 | 이식됨 → `P6` |
| D4 | `concurrency-rules.md` | 좌석 선점 방식, 격리 수준, 멱등키, 재시도, 선점 만료 | 이식됨 → `P5` |
| D5 | `api-guidelines.md` | URL·응답 형식·오류·목록 규약. Zalando 기준 | 이식됨 → `P3` |
| D6 | `refund-policy.md` | 취소 수수료 구간(관람일 기준), 환불 계산, 반올림 | 미착수. 선행 `16` 이 근거 |
| D7 | `time-rules.md` | 저장 시간대, 관람일 계산, 선점 만료 기준 시각 | 이식됨 → `P7` |
| D8 | `testing-strategy.md` | 무엇을 어느 층에서, 동시성 테스트 방식, 컨테이너 | 이식됨 → `P4` |
| D9 | `security-baseline.md` | 세션·시크릿·입력 검증·대기열 토큰 위조 | 이식됨 → `P8` |
| D10 | `observability-rules.md` | 로그 형식·추적 ID·지표 이름 | 이식됨 → `P8` |
| D11 | `event-catalog.md` | 아웃박스 이벤트 이름·페이로드·버전 | 미착수. 선행 `21` |
| D12 | `queue-design.md` | 대기열 자료구조, 입장 속도, 토큰 수명, 새로고침·이탈 처리 | 미착수. 선행 `18` 이 근거 |
| D13 | `performance-goals.md` | 응답 시간·처리량 목표, 측정 방법 | 미착수. 선행 `27` 의 측정값 |
| D14 | `coding-rules.md` | 계층·예외·트랜잭션·SQL·테스트. **Kotlin 으로** | 이식됨 → `P1` |
| D15 | `naming-rules.md` | DB·Kotlin 식별자 | 이식됨 → `P2` |
| D16 | `frontend-rules.md` | 서버·클라이언트 경계, `api.ts` | 이식됨 → `P9` |
| D17 | `screen-rules.md` | 화면 문구·권한 없는 버튼·오류 표시 | 이식됨 → `P9` |
| D18 | `quality-gates.md` | 게이트 목록과 문턱, 리뷰 지적 처분 | 이식됨 → `P10` |
| D19 | `stack.md` | 버전, 공식 문서, 기억으로 쓰면 틀리는 자리 | 이식됨 → `P11` |

## 청크 분할표

파일 1~3개, 커밋 1개 단위다. 번호에 붙은 글자는 나중에 끼워 넣은 것이다.
**미착수 행에는 넷을 적는다** — 축, 강제 지점, 건드리는 자리, 닫힘(초록이면 닫히는 테스트 이름). 넷 다 예보라 치면서 달라질 수 있다.
**완료 행에는 결과만 적는다.** 왜 그렇게 정했나는 `PROGRESS.md` 이력이 든다.

`P` 줄(이식)은 코드 청크 사이에 끼워 친다 — **코드 청크 셋에 문서 청크 하나**를 넘지 않는다(CLAUDE.md 대전제 5).

### 0 — 토대

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 0 | 저장소 뼈대 | `CLAUDE.md`·`PLAN.md`·`PROGRESS.md`·훅·스킬·스크립트·CI·compose·ADR 0001·0002·기준 문서 이식 | 완료 |
| 1 | backend 골격 | 완료 — Kotlin 2.3.21 + Boot 4.1.1 + Gradle 9.7.1, JDK 25 툴체인. `V1__baseline.sql`(DB 시간대 UTC), `GET /api/health`(앱·DB·적용 마이그레이션 수). 테스트 두 레인(`test` 는 `db` 태그 제외, `integrationTest` 는 포함, `build` 가 둘 다). `PostgresTestBase`(Testcontainers postgres:17, `@ServiceConnection`). `HealthControllerTest` 가 마이그레이션 수를 1 로 고정 — 새 `V*` 마다 올린다 | 완료 |
| 2 | CI 첫 초록 | 완료 — `backend/gradlew` 실행 비트. 네 잡(backend·frontend·secrets·docs) 초록, backend 1분 43초. `main` 가지 보호: 네 잡 필수, force push·삭제 금지, 관리자는 안 막는다(CI 설정이 깨졌을 때 저장소가 잠기지 않게) | 완료 |
| 3 | 계정·인증 포팅 | 완료 — `V2__account.sql`(`account` 한 테이블, 역할 컬럼 셋 check, 살아있는 행 check, `lower(email)` 부분 유일 인덱스, 길이 check 254·50). `error/`(ErrorCode 10개 `tag:` type, TicketException, ProblemFactory, ProblemEntryPoint 401+`WWW-Authenticate: Session`, ApiExceptionHandler 가 프레임워크 오류도 우리 type 으로). `auth/`(SecurityConfig 세션+CSRF 쿠키, TicketUserDetailsService 해시 소거, AuthController signup/login/logout, SignupService, Password 15~64 ASCII, EmailAddress 254, 절대 만료 12h 필터, 생존 필터). `account/MeController`. `ArchitectureTest` 3규칙(순환 금지·웹 애너테이션은 Controller 만·ProblemDetail 은 error 만). 로그인 실패 카운터는 `3a` 로 뺐다 | 완료 |
| 3a | 로그인 실패 카운터 | ProjectShop `LoginAttemptService`(이메일+IP 5회 → 차단, 문구는 일반 실패와 같게). Redis 가 필요해서 `21` 뒤로. **축**: `D9`(OWASP 계정 열거·무차별 대입). **강제 지점**: 테스트(5회 뒤 맞는 비밀번호도 401, 본문이 일반 실패와 같다). **건드리는 자리**: 신설 `auth/LoginAttemptService`, `AuthController` 수정. **닫힘**: `LoginAttemptTest.blocked_looks_like_ordinary_failure` | 21 |
| 3b | 비밀번호 블록리스트 | NIST SP 800-63B 의 SHALL — 유출·사전 단어 목록과 대조해 거절. 목록 출처(HIBP 오프라인 상위 N 또는 자체 파일)와 검사 시점(가입·변경)을 정한다. **축**: 표준(NIST 800-63B §3.1.1.2). **강제 지점**: 앱 검증(`Password` 옆 커스텀 제약) + 테스트. DB 로는 못 내린다. **건드리는 자리**: 신설 `auth/PasswordBlocklist`, `Password` 수정, 목록 파일. **닫힘**: `PasswordBlocklistTest.common_password_rejected` | 3 |
| 4 | 감사·동의 포팅 | 완료 — `V3__audit_consent.sql`(`audit_log`, `consent_item`, `account_consent`, `current_consent` 뷰, 항목 시드 셋). 트리거가 감사 행의 `update` 를 전부 막고 `delete` 는 **보존 3년이 지난 행만** 연다 — 전부 막으면 파기가 못 돌고 전부 열면 은폐가 된다. `AuditLog` 가 `ATTEMPT`(별도 트랜잭션)와 `OUTCOME`(같은 트랜잭션)을 가른다 — 실패한 로그인은 롤백에 쓸리면 안 된다. `ConsentService` 는 담긴 것만 적어 거부(false 행)와 무응답(행 없음)을 가른다. `GET /api/consent-items` 공개 | 완료 |
| 5 | 관측 포팅 | 완료 — `observability/RequestLogFilter`(요청당 한 줄, `/actuator/` 는 제외, 보안 필터 바깥이라 401·403 도 남는다), `logback-spring.xml`(UTC·`traceId` 뒤 6자리·`spanId` 앞 4자리, 파일 7일·200MB), `application.yml` 에 W3C 전파·샘플링 1.0. `ProblemFactory` 가 `Tracer` 에서 `trace_id` 를 넣는다 — 청크 3 이 비워 둔 자리. `RequestTraceTest` 6개가 응답의 ID 와 로그의 ID 가 같음을 잰다 | 완료 |
| 6 | 이식 첫 묶음 `P1`·`P2` | 완료 — `coding-rules.md`·`naming-rules.md` 를 **실물 기준으로 새로 썼다**(사용자 선택). 49KB+11KB → 17KB+4KB. 예시가 전부 청크 3~5 의 실제 코드고, 여기 없는 기능(정산·반품·다중 셀러)을 규율하던 절과 Java 문법 절을 버렸다. 살린 뼈대: 계층·예외·트랜잭션·값·SQL·마이그레이션·열거값·길이 상한·주석·테스트·패키지·설정·`public`·불변식·**규칙의 우선순위(축 1·2)**·접근성 | 완료 |

### 1 — 공연·좌석

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 7 | 기획사·공연장 스키마 | 완료 — `V4__venue.sql`(`organizer`·`organizer_member`·`venue`·`hall`·`seat`). 좌석은 `(hall_id, section, row_label, seat_number)` 유일이고 구역 코드는 `F1-A` 꼴 check 다. **소속에 트리거 둘을 건다** — 역할이 기획사가 아닌 계정의 소속을 막고, 소속된 계정의 역할을 뒤늦게 내리는 것도 막는다(한쪽만 걸면 다른 쪽으로 구멍이 열린다). `seat` 는 수정·삭제하지 않는다 — 회차가 이 행을 복제한다(청크 9) | 완료 |
| 8 | 공연·회차·등급 | `event`·`performance`·`seat_grade`·`seat_grade_map`(홀 좌석 → 등급). 회차 상태 `DRAFT→OPEN→CLOSED`. **축**: `D3`(회차 상태). **강제 지점**: 제약(가격 ≥ 0, 회차 시각 > 판매 시작). **건드리는 자리**: `V5__event.sql`, 신설 `event/`. **닫힘**: `PerformanceStateTest` | 7 |
| 9 | 회차 오픈 = 좌석 복제 | 회차를 OPEN 하면 홀 좌석을 `performance_seat` 로 복제(상태 AVAILABLE, 등급·가격 박제). **축**: `D2`(왜 복제인가 — 등급·가격이 회차마다 다르고 상태가 회차 단위다). **강제 지점**: 제약(`performance_id, seat_id` 유일) + 트랜잭션. **건드리는 자리**: `V6__performance_seat.sql`, `event/PerformanceOpenService`. **닫힘**: `PerformanceOpenTest.open_copies_every_seat_once` | 8 |
| 10 | 좌석 현황 조회 | `GET /api/performances/{id}/seats` — 구역별 상태. 응답 계약이 좌석도 UI 의 입력이다. **축**: `D5`(목록·캐시 헤더). **강제 지점**: 스냅샷 테스트(응답 형식). **건드리는 자리**: `event/SeatQuery`·컨트롤러. **닫힘**: `SeatQueryTest` | 9 |
| 11 | 기획사 API | 공연·회차 등록·오픈 API, 기획사 권한. **축**: `D5`·역할. **강제 지점**: 테스트(다른 기획사 공연 수정 403). **건드리는 자리**: `event/` 컨트롤러. **닫힘**: `OrganizerScopeTest` | 8 |
| 12 | 시드·데모 | 공연장 1(홀 2, 좌석 2천), 공연 2, 회차 4, 계정 6. `local` 프로필에서만. **축**: 관례. **강제 지점**: 기동 테스트(빈 DB 에서 시드까지 뜬다). **건드리는 자리**: `db/seed/`. **닫힘**: `SeedBootTest` | 9 |

### 2 — 예매 동시성 (핵심)

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 13 | 좌석 선점 (단일 인스턴스) | `POST /api/reservations` — 좌석 N개를 `UPDATE … WHERE status='AVAILABLE'` 조건부로 HELD, 갱신 행 수가 N 이 아니면 롤백. `reservation(HELD, held_until)`. **축**: `D4`(조건부 UPDATE 가 락 대신이다) + 표준(READ COMMITTED 에서 왜 충분한가). **강제 지점**: 제약(`performance_seat` 상태 enum·`held_until` not null when HELD) + 동시성 테스트. **건드리는 자리**: `V7__reservation.sql`, 신설 `reservation/`. **닫힘**: `SeatHoldConcurrencyTest.hundred_threads_one_winner` | 10 |
| 14 | 선점 만료 스윕 | `held_until` 지난 HELD 를 AVAILABLE 로. 스케줄러 + 멱등. 예매는 EXPIRED. **축**: `D7`(기준 시각) + `D4`(스윕과 결제 확정의 경합). **강제 지점**: 조건부 UPDATE(확정된 것을 안 되돌린다) + 테스트. **건드리는 자리**: `reservation/HoldSweeper`. **닫힘**: `HoldSweeperTest.does_not_release_confirmed` | 13 |
| 15 | 1인 N매·중복 선점 제한 | 회차당 계정당 최대 매수, 같은 계정의 살아있는 HELD 중복 금지. **축**: `D4`. **강제 지점**: 제약(부분 유일 인덱스 — 살아있는 예매만) 우선, 안 되면 앱 검증 + 테스트. **건드리는 자리**: `V8`, `reservation/`. **닫힘**: `ReservationLimitTest` | 13 |
| 16 | 결제 포팅 + 확정 | ProjectShop `MockPaymentGateway` 개조. 결제 성공 → HELD→RESERVED, 실패·시간초과 → 해제. 멱등키. **축**: `D3`·`D4`(멱등). **강제 지점**: 제약(멱등키 유일) + 상태 전이 테스트. **건드리는 자리**: 신설 `payment/`, `V9`. **닫힘**: `PaymentConfirmTest.expired_hold_cannot_confirm` | 14 |
| 17 | 취소·환불 + `D6` | 관람일 기준 수수료 구간표, 환불 계산, 좌석 해제. `refund-policy.md` 를 이 청크가 쓴다(근거가 여기서 생긴다). **축**: `D6`·`D7`. **강제 지점**: 테이블(수수료 구간을 코드가 아니라 행으로) + 테스트. **건드리는 자리**: `V10__refund.sql`, `payment/Refund*`, 신설 `doc/reference/refund-policy.md`. **닫힘**: `RefundPolicyTest` | 16 |
| 18 | 발권 | RESERVED 예매에 티켓 번호(외부 노출 식별자, `identifier-rules.md`). 조회 API. **축**: `D5`·식별자 규약. **강제 지점**: 제약(티켓 번호 유일). **건드리는 자리**: `V11__ticket.sql`, `reservation/Ticket*`. **닫힘**: `TicketIssueTest` | 16 |
| 19 | 동시성 계측 | 100·1000 스레드 선점 테스트를 Testcontainers 에서 돌리고 수치(성공 1·실패 N·소요)를 `doc/notes/` 에 적는다. 낙관락(`version`)·비관락(`FOR UPDATE`)·조건부 UPDATE 셋을 같은 테스트로 비교 → ADR 0003. **축**: 측정. **강제 지점**: 없다 — 기록이다. **건드리는 자리**: `reservation/` 테스트, `doc/notes/lock-comparison.md`, `doc/adr/0003`. **닫힘**: ADR 에 세 수치가 있다 | 13 |
| 20 | 이식 `P5`·`P6` | `concurrency-rules.md`·`state-machines.md` 를 예매 것으로 다시 쓴다. 13~18 이 근거다. **축**: 규약. **강제 지점**: 전이표를 코드에 선언하고 테스트가 문서와 대조. **건드리는 자리**: 두 문서. **닫힘**: `StateMachineDocTest` | 17 |

### 3 — 대기열

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 21 | 대기열 진입·순번 | Redis ZSET(score=진입 시각) 회차 단위. `POST /api/queue/{performanceId}` → 순번·예상 대기. Redis Testcontainers. **축**: `D12`(이 청크가 초안을 쓴다). **강제 지점**: 테스트(순번 단조). **건드리는 자리**: 신설 `queue/`, `doc/reference/queue-design.md`. **닫힘**: `QueueRankTest` | 13 |
| 22 | 입장 스케줄러·활성 토큰 | N초마다 앞 M명을 활성 집합으로 옮기고 토큰(TTL) 발급. **축**: `D12`. **강제 지점**: TTL + 테스트(만료 토큰 거부). **건드리는 자리**: `queue/AdmissionScheduler`. **닫힘**: `AdmissionTest` | 21 |
| 23 | 대기열 관문 | 활성 토큰 없이 예매 API 를 부르면 429/403. 필터. **축**: `D9`(토큰 위조·재사용). **강제 지점**: 필터 + 테스트. **건드리는 자리**: `queue/QueueGateFilter`. **닫힘**: `QueueGateTest.no_token_rejected` | 22 |
| 24 | 이탈·새로고침 | 하트비트 없으면 제거, 새로고침해도 순번 유지. **축**: `D12`. **강제 지점**: TTL. **건드리는 자리**: `queue/`. **닫힘**: `QueueHeartbeatTest` | 22 |

### 4 — 비동기 분리

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 25 | 아웃박스 | `outbox` 테이블, 예매 확정 트랜잭션에 이벤트 행 커밋, 릴레이가 Spring 이벤트로 발행. `event-catalog.md`(`D11`) 초안. **축**: 표준(Transactional Outbox). **강제 지점**: 트랜잭션 + 테스트(확정 없이 이벤트 없음). **건드리는 자리**: `V12__outbox.sql`, 신설 `outbox/`. **닫힘**: `OutboxAtomicityTest` | 16 |
| 26 | 알림 포팅 (소비자 1) | ProjectShop `notification` 개조 — 예매 확정·취소 메일(모의 발송, 본문 이력). **축**: `D11`. **강제 지점**: 멱등(이벤트 ID 유일) + 테스트. **건드리는 자리**: 신설 `notification/`, `V13`. **닫힘**: `NotificationIdempotencyTest` | 25 |
| 27 | 정산 집계 (소비자 2) | 회차 종료 후 기획사별 매출·수수료 집계. **둘째 소비자다 — 다음 청크가 Kafka 를 든다.** **축**: `D11`. **강제 지점**: 테스트. **건드리는 자리**: 신설 `settlement/`, `V14`. **닫힘**: `SettlementAggregateTest` | 26 |
| 28 | Kafka 도입 | compose 에 Kafka, 릴레이가 Kafka 로 발행, 소비자 둘을 Kafka 리스너로. ADR 0004(왜 지금인가). **축**: 관례(토픽 이름·파티션 키) + `D11`. **강제 지점**: Kafka Testcontainers 테스트. **건드리는 자리**: `docker-compose.yml`, `outbox/`, `notification/`, `settlement/`. **닫힘**: `KafkaRelayTest` | 27 |
| 29 | 재시도·DLQ·멱등 소비 | 소비 실패 재시도, 초과 시 DLQ, 중복 전달에 멱등. **축**: 표준(at-least-once). **강제 지점**: 테스트(같은 이벤트 두 번 → 한 번 처리). **건드리는 자리**: `outbox/`·소비자. **닫힘**: `ConsumerIdempotencyTest`·`DlqTest` | 28 |

### 5 — 관측·부하·다중 인스턴스

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 30 | 지표·대시보드 | Micrometer → Prometheus, Grafana 대시보드(선점 성공률·대기열 길이·응답 시간). compose 추가. **축**: `D10`. **강제 지점**: 테스트(지표 이름이 문서와 같다). **건드리는 자리**: `docker-compose.yml`, `observability/`, `docker/grafana/`. **닫힘**: `MetricNamesTest` | 13·21 |
| 31 | k6 시나리오 | 동시 1만 접속·좌석 1천 경쟁. 결과를 `doc/notes/load-1.md` 에. **축**: 측정. **강제 지점**: 없다. **건드리는 자리**: 신설 `load/`. **닫힘**: 리포트에 p95·성공 좌석 수·오류율이 있다 | 23·30 |
| 32 | `D13` 성능 목표 | 31 의 측정값으로 목표를 정한다. **축**: 측정값. **강제 지점**: 없다 — 문서. `35` 가 검증. **건드리는 자리**: 신설 `performance-goals.md`. **닫힘**: 수치 셋(p95·처리량·오류율) | 31 |
| 33 | 다중 인스턴스 | `docker compose up --scale app=3` + nginx. 분산 환경에서 13·22 가 깨지나 본다 — 스케줄러 중복 실행이 첫 후보(ShedLock 또는 Redis 락). **축**: `D4`. **강제 지점**: 테스트(스윕·입장 스케줄러가 인스턴스 셋에서 한 번만). **건드리는 자리**: `docker-compose.yml`, `docker/nginx/`, 스케줄러. **닫힘**: `SchedulerSingleRunTest` | 22·30 |
| 34 | Redisson 분산락 비교 | 조건부 UPDATE vs Redisson 락 을 33 환경에서 재고 ADR 0005. **축**: 측정. **강제 지점**: 없다. **건드리는 자리**: `reservation/`, `doc/adr/0005`. **닫힘**: ADR 에 두 수치 | 33 |
| 35 | 부하 2차 | 33 환경에서 31 재실행, `D13` 대조. **축**: `D13`. **강제 지점**: 없다. **건드리는 자리**: `doc/notes/load-2.md`. **닫힘**: 목표 대비 표 | 32·33 |

### 6 — k8s

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 36 | kind 클러스터 + 매니페스트 | kind 설정, app Deployment(replicas 3)·Service, Postgres·Redis StatefulSet, ConfigMap·Secret. **축**: 관례(k8s 기본 리소스). **강제 지점**: `kubectl rollout status` 스크립트. **건드리는 자리**: 신설 `k8s/`. **닫힘**: `scripts/k8s-smoke.sh` 가 `/api/health` 200 | 33 |
| 37 | Helm 차트 | 36 을 차트로. values 로 replicas·이미지 태그. **축**: 관례. **강제 지점**: `helm lint` + `helm template` 스냅샷. **건드리는 자리**: 신설 `k8s/chart/`. **닫힘**: `helm lint` 초록 | 36 |
| 38 | 무중단 배포·HPA | RollingUpdate 중 k6 오류율 0, CPU 기준 HPA. **축**: 측정. **강제 지점**: readiness probe. **건드리는 자리**: `k8s/chart/`. **닫힘**: `doc/notes/rollout.md` 에 배포 중 오류율 | 37·31 |

### 7 — 화면

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 39 | frontend 골격 포팅 | ProjectShop Next.js 뼈대·`api.ts`·login/signup/me. **축**: `D16`. **강제 지점**: `tsc`·lint. **건드리는 자리**: 신설 `frontend/`. **닫힘**: `npm run build` 초록 + 로그인 e2e | 3 |
| 40 | 공연 목록·상세 | 서버 컴포넌트. **축**: `D16`·`D17`. **강제 지점**: 스냅샷. **건드리는 자리**: `frontend/src/app/events`. **닫힘**: `events.test.tsx` | 39·11 |
| 41 | 좌석도 | 구역·열·번호를 SVG/캔버스로, 상태 색, 선택 N개. **축**: `D17`·접근성(WCAG 색 대비·키보드). **강제 지점**: axe 테스트. **건드리는 자리**: `frontend/src/components/SeatMap`. **닫힘**: `seat-map.test.tsx` | 40·10 |
| 42 | 예매·결제 흐름 | 선점 → 5분 카운트다운 → 모의 결제 → 발권 확인. **축**: `D17`. **강제 지점**: e2e. **건드리는 자리**: `app/reserve`·`app/checkout`. **닫힘**: `reserve.spec.ts` | 41·18 |
| 43 | 대기열 화면 | 순번·예상 대기·자동 입장. 폴링 vs SSE 를 여기서 정한다. **축**: `D12`. **강제 지점**: e2e. **건드리는 자리**: `app/queue`. **닫힘**: `queue.spec.ts` | 42·23 |
| 44 | 마이페이지·취소 | 예매 내역·취소·환불 금액 미리보기. **축**: `D6`·`D17`. **강제 지점**: e2e. **건드리는 자리**: `app/me`. **닫힘**: `cancel.spec.ts` | 42·17 |
| 45 | 기획사 화면 | 공연·회차 등록·오픈·매출. **축**: `D17`. **강제 지점**: e2e. **건드리는 자리**: `app/organizer`. **닫힘**: `organizer.spec.ts` | 40·27 |

### 8 — 품질·이식 나머지

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 46 | codeql·claude-review·e2e 워크플로 | ProjectShop 셋 이식. Kotlin 은 CodeQL `java-kotlin`. **축**: `D18`. **강제 지점**: CI. **건드리는 자리**: `.github/workflows/`. **닫힘**: 세 잡 초록 | 2·39 |
| 47 | detekt | SpotBugs 자리에 detekt. **축**: `D18`. **강제 지점**: 빌드 실패. **건드리는 자리**: `build.gradle.kts`, `config/detekt/`. **닫힘**: `gradlew detekt` 초록 | 1 |
| P3 | `api-guidelines.md` 이식 | 예시를 예매 API 로. **축**: 규약(코드에 성립한 것). **강제 지점**: 스냅샷 테스트가 응답 형식을 든다. **건드리는 자리**: 그 문서, 머리말 삭제. **닫힘**: `doc-lint.sh` 통과 + 머리말 없음 | 10 |
| P4 | `testing-strategy.md` 이식 | 동시성 테스트 층·Redis 컨테이너 추가. **축**: 규약. **강제 지점**: 레인 이름이 `build.gradle.kts` 와 같다는 테스트. **건드리는 자리**: 그 문서. **닫힘**: `doc-lint.sh` 통과 + 머리말 없음 | 13·21 |
| P7 | `time-rules.md` 이식 | 관람일·선점 만료 기준. **축**: 규약. **강제 지점**: 없다 — 문서. `14`·`17` 의 테스트가 값을 든다. **건드리는 자리**: 그 문서. **닫힘**: `doc-lint.sh` 통과 + 머리말 없음 | 14 |
| P8 | `security-baseline.md`·`observability-rules.md` 이식 | 대기열 토큰 추가. **축**: 표준(OWASP) + 규약. **강제 지점**: 없다 — 문서. `23` 의 필터 테스트가 든다. **건드리는 자리**: 두 문서. **닫힘**: `doc-lint.sh` 통과 + 머리말 없음 | 23 |
| P9 | `frontend-rules.md`·`screen-rules.md` 이식 | 예매·좌석도 화면 규약. **축**: 규약 + WCAG. **강제 지점**: lint 접근성 규칙. **건드리는 자리**: 두 문서. **닫힘**: `doc-lint.sh` 통과 + 머리말 없음 | 41 |
| P10 | `quality-gates.md` 이식 | detekt·CodeQL Kotlin. **축**: 규약. **강제 지점**: CI 잡 목록이 문서와 같다. **건드리는 자리**: 그 문서. **닫힘**: `doc-lint.sh` 통과 + 머리말 없음 | 46·47 |
| P11 | `stack.md`·`identifier-rules.md`·`external-references.md` 이식 | 버전표 재작성, 쇼핑 참조 삭제. **축**: 규약. **강제 지점**: `StackVersionConsistencyTest`(버전표와 빌드 파일 대조). **건드리는 자리**: 세 문서. **닫힘**: 그 테스트 초록 + 머리말 없음 | 1 |
| 48 | Railway 배포(선택) | 사용자가 켜면. **축**: 관례. **강제 지점**: 없다. **건드리는 자리**: `Dockerfile`·env. **닫힘**: 공개 URL 의 `/api/health` 200 | 38 |

## 이 계획을 고칠 때

- 완료 행은 안 고친다. 그때 그렇게 쳤다는 기록이다
- 새 행은 넷을 다 적는다. 빠지면 `doc-lint.sh` 가 빨갛다
- 청크를 흡수·분할하면 이력에 밝힌다
