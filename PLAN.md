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

**좌석 상태의 단일 진실은 `performance_seat.status` 다.** `AVAILABLE → held → reserved`, held 는 `held_until` 이 지나면 스윕이 되돌린다.
Redis 는 대기열과 캐시고, 좌석을 확정하지 않는다.

## 기준 문서

여러 청크가 같이 참조하는 결정이다. 조건(선행 완료 + 정할 근거가 코드에 있음)이 찬 것부터 코드 청크보다 먼저 쓴다.
이식 머리말이 붙은 문서는 `P` 줄 이식 청크가 닫아야 「완료」다.

| # | 문서 | 무엇을 정하나 | 상태 |
|---|---|---|---|
| D1 | `doc/README.md`·`glossary.md` | 문서 트리, 도메인 용어 한↔영 | 완료 |
| D2 | `domain-model.md` | 엔티티 관계·경계·수명 | 완료(초안) |
| D3 | `state-machines.md` | 예매·결제·환불·회차·대기열 토큰의 상태와 전이 | 완료(12a) |
| D4 | `concurrency-rules.md` | 조건부 UPDATE 근거, 잠금 순서, 멱등키, 불변식, 스윕 경합 | 완료(12b) |
| D5 | `api-guidelines.md` | 경로 표, 상태 코드, **오류 `type` 목록(계약)**, 403/404, 목록, 헤더. Zalando 기준·벗어난 것 셋 | 완료(P3) |
| D6 | `refund-policy.md` | 구간표를 행으로(`refund_fee_tier`), 예매 단위 한 번 반올림, `refund` 행 박제, 불변식, 사유 셋, 법 고지 자리 | 완료 |
| D7 | `time-rules.md` | 저장 UTC·판단 KST, **시계는 DB**, 관람일 N일 전 = KST 달력일 차, 박제 | 완료(P7) |
| D8 | `testing-strategy.md` | 층·레인 셋(`measure` 추가), 격리, `ConcurrencyTestBase`, 승자 하나 판정, 날짜 고정 | 완료(P4) |
| D9 | `security-baseline.md` | OWASP Top 10 2025 대응표, 비밀번호·세션·쿠키·CSRF·토큰·멱등키, 노출면, A10 | 완료(P8) |
| D10 | `observability-rules.md` | 감사 vs 관측, 추적 ID, 형식·레벨, 개인정보 금지, 지표 이름 규약 | 완료(P8) |
| D11 | `event-catalog.md` | 아웃박스 이벤트 이름·페이로드·버전 | 미착수. 선행 `21` |
| D12 | `queue-design.md` | 대기열 자료구조, 입장 속도·정원 등식, 토큰, 관문 범위, 이탈 | 완료(12c) |
| D13 | `performance-goals.md` | 응답 시간·처리량 목표, 측정 방법 | 미착수. 선행 `27` 의 측정값 |
| D14 | `coding-rules.md` | 계층·예외·트랜잭션·SQL·테스트. **Kotlin 으로** | 이식됨 → `P1` |
| D15 | `naming-rules.md` | DB·Kotlin 식별자 | 이식됨 → `P2` |
| D16 | `frontend-rules.md` | 서버·클라이언트 경계, `api.ts` | 이식됨 → `P9` |
| D17 | `screen-rules.md` | 화면 문구·권한 없는 버튼·오류 표시 | 이식됨 → `P9` |
| D18 | `quality-gates.md` | 게이트 목록과 문턱, 리뷰 지적 처분 | 이식됨 → `P10` |
| D19 | `stack.md` | 버전, 공식 문서, 기억으로 쓰면 틀리는 자리 | 이식됨 → `P11` |
| D20 | `seat-read-model.md` | 좌석 현황 계약, 버전·스냅샷·델타, ETag | 완료(12d) |
| D21 | `settlement-rules.md` | **정책 표 + 명세 항목.** 회차당 정산서 하나, 종료 뒤 D+7, 불변식 셋 | 완료 |

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
| 4a | 감사 로그 권한 분리 | 앱이 쓰는 역할에서 `audit_log` 의 `update`·`delete`·`truncate` 를 회수하고 파기 배치만 다른 역할로 돌린다. **트리거는 테이블 주인이 끌 수 있어서 앱과 같은 역할로는 못 막는다**(`V3` 주석). **축**: `D9`. **강제 지점**: 권한(강제 지점 1위에 가장 가깝다). **건드리는 자리**: 새 `V`, 배포 문서. **닫힘**: `AuditRoleTest.app_role_cannot_delete` | 33 |
| 4 | 감사·동의 포팅 | 완료 — `V3__audit_consent.sql`(`audit_log`, `consent_item`, `account_consent`, `current_consent` 뷰, 항목 시드 셋). 트리거가 감사 행의 `update` 를 전부 막고 `delete` 는 **보존 3년이 지난 행만** 연다 — 전부 막으면 파기가 못 돌고 전부 열면 은폐가 된다. `AuditLog` 가 `ATTEMPT`(별도 트랜잭션)와 `OUTCOME`(같은 트랜잭션)을 가른다 — 실패한 로그인은 롤백에 쓸리면 안 된다. `ConsentService` 는 담긴 것만 적어 거부(false 행)와 무응답(행 없음)을 가른다. `GET /api/consent-items` 공개 | 완료 |
| 5 | 관측 포팅 | 완료 — `observability/RequestLogFilter`(요청당 한 줄, `/actuator/` 는 제외, 보안 필터 바깥이라 401·403 도 남는다), `logback-spring.xml`(UTC·`traceId` 뒤 6자리·`spanId` 앞 4자리, 파일 7일·200MB), `application.yml` 에 W3C 전파·샘플링 1.0. `ProblemFactory` 가 `Tracer` 에서 `trace_id` 를 넣는다 — 청크 3 이 비워 둔 자리. `RequestTraceTest` 6개가 응답의 ID 와 로그의 ID 가 같음을 잰다 | 완료 |
| 6 | 이식 첫 묶음 `P1`·`P2` | 완료 — `coding-rules.md`·`naming-rules.md` 를 **실물 기준으로 새로 썼다**(사용자 선택). 49KB+11KB → 17KB+4KB. 예시가 전부 청크 3~5 의 실제 코드고, 여기 없는 기능(정산·반품·다중 셀러)을 규율하던 절과 Java 문법 절을 버렸다. 살린 뼈대: 계층·예외·트랜잭션·값·SQL·마이그레이션·열거값·길이 상한·주석·테스트·패키지·설정·`public`·불변식·**규칙의 우선순위(축 1·2)**·접근성 | 완료 |

### 1 — 공연·좌석

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 7 | 기획사·공연장 스키마 | 완료 — `V4__venue.sql`(`organizer`·`organizer_member`·`venue`·`hall`·`seat`). 좌석은 `(hall_id, section, row_label, seat_number)` 유일이고 구역 코드는 `F1-A` 꼴 check 다. **소속에 트리거 둘을 건다** — 역할이 기획사가 아닌 계정의 소속을 막고, 소속된 계정의 역할을 뒤늦게 내리는 것도 막는다(한쪽만 걸면 다른 쪽으로 구멍이 열린다). `seat` 는 수정·삭제하지 않는다 — 회차가 이 행을 복제한다(청크 9) | 완료 |
| 8 | 공연·회차·등급 | 완료 — `V5__event.sql`(`event`·`seat_grade`·`seat_grade_map`·`performance`). **등급은 공연 단위, 매핑은 구역 단위**(ADR 0003). 가격은 원 단위 정수고 회차 오픈 때 박제된다. 회차 상태 전이를 **트리거가 한 방향으로 묶는다** — `open→draft` 를 열면 복제된 좌석과 팔린 예매가 남은 채로 「아직 안 연 회차」가 된다. `sales_open_at < starts_at` check 가 살 수 없는 회차를 막는다 | 완료 |
| 9 | 회차 오픈 = 좌석 복제 | 완료 — `V6__performance_seat.sql` + `PerformanceOpenService`. 좌석 복제와 상태 변경이 **한 트랜잭션**이다. `(performance_id, seat_id)` 유일이 두 번 복제를 막고, 상태·부속값 check 가 `held` 인데 만료 시각이 없는 행을 막는다. **등급이 안 붙은 구역과 좌석 없는 홀은 오픈을 거절한다** — 스키마로 못 보는 조건이라 서비스가 막고, 안 막으면 좌석도에 구멍이 뚫린 채 판매가 시작된다 | 완료 |
| 10 | 좌석 현황 조회 | 완료 — `GET /api/performances/{id}/seats`(공개). `SeatQuery`(읽기, 트랜잭션 없음)·`SeatController`·`PerformanceSeatStatus`. 구역 이름은 코드에서 파생. 버전은 Redis 전까지 `max(updated_at)`. `ETag`/`If-None-Match` → 304, `Cache-Control: no-cache`. draft·없음은 404. 닫힘: `SeatQueryTest`(스냅샷 `SeatQueryTest.full.json`) + `SeatVersionTest`(커밋 레인) | 완료 |
| 11 | 기획사 API | 공연·회차 등록·오픈 API, 기획사 권한. **축**: `D5`·역할. **강제 지점**: 테스트(다른 기획사 공연 수정 403). **건드리는 자리**: `event/` 컨트롤러. **닫힘**: `OrganizerScopeTest` | 8 |
| 12 | 시드·데모 | 공연장 1(홀 2, 좌석 2천), 공연 2, 회차 4, 계정 6. `local` 프로필에서만. **축**: 관례. **강제 지점**: 기동 테스트(빈 DB 에서 시드까지 뜬다). **건드리는 자리**: `db/seed/`. **닫힘**: `SeedBootTest` | 9 |
| 12a | `D3` 예매 상태기계 | 완료 — `state-machines.md` 재작성(P6 흡수). `held→paying→reserved→cancelled`, `→expired`. **`paying` 이 스윕과 승인의 경합을 푼다**(ADR 0004). 좌석 상태는 예매의 투영. 회차에 `cancelled` 추가. 자동 전이 셋(스윕·타임아웃·종료) 전부 멱등 | 완료 |
| 12b | `D4` 동시성 규약 | 완료 — `concurrency-rules.md` 재작성(P5 흡수). Read Committed 로 충분한 근거(§13.2.1 WHERE 재평가), 다중 행은 **id 오름차순 `for update` 먼저**(교착 방지), 문장 순서(예매 행 → 잠금 → 조건부 UPDATE → 행 수 비교), `reservation_seat`=기록·`reservation_id`=포인터, 합계 불변식은 지연 제약 트리거, 멱등키(선점·결제 필수), 재시도는 40P01 만 1회 | 완료 |
| 12c | `D12` 대기열 설계 | 완료 — `queue-design.md` 신설. 등식(R ≤ 풀/지연), 키 다섯, `ZADD NX` 로 순번 유지, Lua 원자 입장, 무작위 토큰(반납 가능), **관문은 선점만**(ADR 0004), 선점 성공 시 반납, Redis 죽으면 관문 닫힘(503) | 완료 |
| 12d | `D20` 좌석 읽기 모델 | 완료 — `seat-read-model.md` 신설 + ADR 0005. 커밋 뒤 `INCR` 버전, 버전 키 스냅샷(TTL 10s), STREAM 변경 로그, `ETag`/304, `?since=` 델타·410. DB 는 정하는 일만, Redis 는 보여주는 일만 | 완료 |
| 12e | 세션 저장소 결정 | 완료 — ADR 0004 에 흡수. Spring Session Redis. JWT(즉시 취소 불가)·sticky(문제를 숨긴다) 버림 | 완료 |
| 12f | 동시성 테스트 바탕 | 완료 — `ConcurrencyTestBase`(`@Transactional` 없음, 애너테이션은 `PostgresTestBase` 와 동일해 컨텍스트 하나). `runConcurrently(n)` 이 출발선을 맞추고 스레드별 `Result` 를 돌려준다. 정리는 `concurrency-` 접두(계정·기획사·공연장)를 앞뒤 한 트랜잭션에서 `restrict` 순서로. 닫힘: `ConcurrencyTestBaseTest` 둘(스레드 100 커밋 100, 접두만 지움) | 완료 |

### 2 — 예매 동시성 (핵심)

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 13 | 좌석 선점 (단일 인스턴스) | `POST /api/performances/{id}/reservations`(`D5` — 관문이 회차 id 를 경로에서 읽는다) — `D4` 문장 순서 그대로: 예매 행(`held`) → id 오름차순 `for update` → 조건부 UPDATE(회차 `open` 포함) → 행 수 ≠ N 이면 롤백 → `reservation_seat` 기록. **`Idempotency-Key` 필수**, 응답 재생. `performance_seat.reservation_id` 에 외래키. 합계는 지연 제약 트리거. 관문 자리(`X-Admission-Token`)는 23 이 채우도록 비워 둔다. **축**: `D4`·`D3`. **강제 지점**: 제약(외래키·지연 트리거·멱등키 유일) + 동시성 테스트. **건드리는 자리**: `V7__reservation.sql`, 신설 `reservation/`·`idempotency/`. **닫힘**: `SeatHoldConcurrencyTest.hundred_threads_one_winner` + `ReservationSeatConsistencyTest` | 10·12a·12b·12f |
| 14 | 선점 만료 스윕 | `held_until` 지난 held 를 AVAILABLE 로. 스케줄러 + 멱등. 예매는 expired. **축**: `D7`(기준 시각) + `D4`(스윕과 결제 확정의 경합). **강제 지점**: 조건부 UPDATE(확정된 것을 안 되돌린다) + 테스트. **건드리는 자리**: `reservation/HoldSweeper`. **닫힘**: `HoldSweeperTest.does_not_release_confirmed` | 13 |
| 15 | 1인 N매·중복 선점 제한 | 회차당 계정당 최대 매수, 같은 계정의 살아있는 held 중복 금지. **축**: `D4`. **강제 지점**: 제약(부분 유일 인덱스 — 살아있는 예매만) 우선, 안 되면 앱 검증 + 테스트. **건드리는 자리**: `V8`, `reservation/`. **닫힘**: `ReservationLimitTest` | 13 |
| 16 | 결제 포팅 + 확정 | ProjectShop `MockPaymentGateway` 개조(카드번호가 결과를 정한다, ADR 0003). **트랜잭션 셋**: `held→paying`(`paying_until`=+3분) / PG 호출(트랜잭션 밖) / 결과 반영 — 승인은 `where status='paying' and paying_until >= now()` 조건부, 0행이면 PG 취소 + `payment_late` 감사. 거절은 `→held`. 타임아웃 스케줄러가 `paying→expired`. **축**: `D3`·`D4`. **강제 지점**: 제약(`approved` 예매당 부분 유일·멱등키) + 전이 테스트. **건드리는 자리**: 신설 `payment/`, `V9`. **닫힘**: `PaymentConfirmTest.expired_hold_cannot_confirm`·`sweeper_does_not_touch_paying` | 14·12a |
| 16a | 판매 마감·자동 종료 | `performance.sales_close_at`(기본 `starts_at - 1h`), check `sales_open_at < sales_close_at < starts_at`. 스케줄러(1분)가 `open→closed`, 남은 `held`·`paying` 을 `expired`, 대기열 키 삭제. **축**: `D3`·`D7`. **강제 지점**: check + 조건부 UPDATE + 테스트. **건드리는 자리**: 새 `V`, `event/PerformanceCloser`. **닫힘**: `PerformanceCloseTest.held_seats_expire_on_close` | 14 |
| 17 | 취소·환불 + `D6` | 관람일 기준 수수료 구간표, 환불 계산, 좌석 해제. `D6` 대로 — `refund_fee_tier` 행 시드, `refund` 에 `days_before`·`tier_rate` 박제, 지연 트리거(`fee + refund = payment`). **축**: `D6`·`D7`. **강제 지점**: 테이블(수수료 구간을 코드가 아니라 행으로) + 테스트. **건드리는 자리**: `V10__refund.sql`, `payment/Refund*`, `D6` 는 이미 있다. **닫힘**: `RefundPolicyTest` | 16 |
| 17a | 회차 취소 | 기획사가 `open` 회차를 `cancelled` 로. 모든 예매 `cancelled`, `reserved` 는 **전액 환불**(구간 무관), 알림 이벤트 일괄(아웃박스). check·전이 트리거에 `cancelled` 추가. **축**: `D3`·`D6`. **강제 지점**: 한 트랜잭션 + 아웃박스 원자성 테스트. **건드리는 자리**: 새 `V`, `event/PerformanceCancelService`, `payment/Refund*`. **닫힘**: `PerformanceCancelTest.every_reservation_refunded_in_full` | 17·25 |
| 18 | 발권 | reserved 예매에 티켓 번호(외부 노출 식별자, `identifier-rules.md`). 조회 API. **축**: `D5`·식별자 규약. **강제 지점**: 제약(티켓 번호 유일). **건드리는 자리**: `V11__ticket.sql`, `reservation/Ticket*`. **닫힘**: `TicketIssueTest` | 16 |
| 19 | 동시성 계측 | 100·1000 스레드 선점 테스트를 Testcontainers 에서 돌리고 수치(성공 1·실패 N·소요)를 `doc/notes/` 에 적는다. 낙관락(`version`)·비관락(`FOR UPDATE`)·조건부 UPDATE 셋을 같은 테스트로 비교 → ADR 0006. **축**: 측정. **강제 지점**: 없다 — 기록이다. **건드리는 자리**: `reservation/` 테스트, `doc/notes/lock-comparison.md`, `doc/adr/0003`. **닫힘**: ADR 에 세 수치가 있다 | 13 |
| 20 | ~~이식 P5·P6~~ | 12a·12b 가 흡수했다 — 두 문서를 코드보다 먼저 썼다(ADR 0004). `StateMachineDocTest`(전이표와 문서 대조)는 13 이 세운다 | 완료 |

### 3 — 대기열

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 21 | 대기열 진입·순번 | Redis ZSET(score=진입 시각) 회차 단위. `POST /api/queue/{performanceId}` → 순번·예상 대기. Redis Testcontainers. **축**: `D12`(이 청크가 초안을 쓴다). **강제 지점**: 테스트(순번 단조). **건드리는 자리**: 신설 `queue/`, `doc/reference/queue-design.md`. **닫힘**: `QueueRankTest` | 13 |
| 22 | 입장 스케줄러·활성 토큰 | N초마다 앞 M명을 활성 집합으로 옮기고 토큰(TTL) 발급. **축**: `D12`. **강제 지점**: TTL + 테스트(만료 토큰 거부). **건드리는 자리**: `queue/AdmissionScheduler`. **닫힘**: `AdmissionTest` | 21 |
| 23 | 대기열 관문 | 활성 토큰 없이 예매 API 를 부르면 429/403. 필터. **축**: `D9`(토큰 위조·재사용). **강제 지점**: 필터 + 테스트. **건드리는 자리**: `queue/QueueGateFilter`. **닫힘**: `QueueGateTest.no_token_rejected` | 22 |
| 24 | 이탈·새로고침 | 하트비트 없으면 제거, 새로고침해도 순번 유지. **축**: `D12`. **강제 지점**: TTL. **건드리는 자리**: `queue/`. **닫힘**: `QueueHeartbeatTest` | 22 |
| 20a | 세션 저장소 Redis | Spring Session Data Redis + `SpringSessionBackedSessionRegistry`. 로그인 코드는 그대로. 정지·탈퇴가 인스턴스를 넘어 세션을 끊는다. **축**: ADR 0004. **강제 지점**: 테스트(컨텍스트 둘이 같은 세션 쿠키를 인정한다). **건드리는 자리**: `SecurityConfig`, `build.gradle.kts`, `application.yml`. **닫힘**: `SharedSessionTest.login_on_one_context_is_seen_by_another` | 21 |
| 5a | 탈퇴·파기 | `DELETE /api/me`(세션 전부 끊기·`deleted_at`), 30일 뒤 파기 배치(이메일·이름·해시 null, 동의 cascade 는 물리 삭제 때), 감사 3년 파기. **축**: `D9` + 개인정보보호법 제21조. **강제 지점**: 트리거(`deleted_at` 계정 로그인 불가 — 있음) + 배치 테스트. **건드리는 자리**: `account/WithdrawalService`·`AccountPurgeBatch`. **닫힘**: `AccountPurgeTest.pii_nulled_after_grace` | 4·20a |
| 5b | 관리자 정지·해제 | `admin` 전용 `POST /api/admin/accounts/{id}/suspend`, 정지 즉시 그 계정 세션 전부 만료, 감사. **축**: `D9`. **강제 지점**: 테스트(정지 직후 다른 컨텍스트의 세션이 401). **건드리는 자리**: `account/AdminController`. **닫힘**: `SuspendTest.other_session_cut_immediately` | 20a |

### 4 — 비동기 분리

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 25 | 아웃박스 | `outbox` 테이블, 예매 확정 트랜잭션에 이벤트 행 커밋, 릴레이가 Spring 이벤트로 발행. 릴레이는 `for update skip locked` 로 가져간다 — 인스턴스 셋이 같은 행을 두 번 안 민다. `event-catalog.md`(`D11`) 초안. **축**: 표준(Transactional Outbox). **강제 지점**: 트랜잭션 + 테스트(확정 없이 이벤트 없음). **건드리는 자리**: `V12__outbox.sql`, 신설 `outbox/`. **닫힘**: `OutboxAtomicityTest` | 16 |
| 26 | 알림 포팅 (소비자 1) | ProjectShop `notification` 개조 — 예매 확정·취소 메일(모의 발송, 본문 이력). **축**: `D11`. **강제 지점**: 멱등(이벤트 ID 유일) + 테스트. **건드리는 자리**: 신설 `notification/`, `V13`. **닫힘**: `NotificationIdempotencyTest` | 25 |
| 27 | 정산 집계 (소비자 2) | `D21` 대로. `settlement_policy`(기본 행 시드)·`settlement`·`settlement_line`, `performance.settlement_policy_id`(오픈 때 박제 — `PerformanceOpenService` 수정), `refund_fee_tier` 는 17 이 만든다. `performance.closed` 사건을 받아 `payout_delay_days` 뒤 회차당 한 장. **둘째 소비자다 — 다음 청크가 Kafka 를 든다.** **축**: `D21`·`D11`. **강제 지점**: 유일(회차당 1)·check(`amount >= 0`)·지연 트리거(합 = 항목 합) + 테스트. **건드리는 자리**: 새 `V`, 신설 `settlement/`, `event/PerformanceOpenService`. **닫힘**: `SettlementAggregateTest.sale_equals_reserved_seat_prices`·`cancelled_performance_settles_to_zero` | 26 |
| 28 | Kafka 도입 | compose 에 Kafka, 릴레이가 Kafka 로 발행, 소비자 둘을 Kafka 리스너로. ADR 0007(왜 지금인가). 파티션 키 = `reservation_id` — 한 예매의 사건이 순서를 지킨다. **축**: 관례(토픽 이름·파티션 키) + `D11`. **강제 지점**: Kafka Testcontainers 테스트. **건드리는 자리**: `docker-compose.yml`, `outbox/`, `notification/`, `settlement/`. **닫힘**: `KafkaRelayTest` | 27 |
| 29 | 재시도·DLQ·멱등 소비 | 소비 실패 재시도, 초과 시 DLQ, 중복 전달에 멱등. **축**: 표준(at-least-once). **강제 지점**: 테스트(같은 이벤트 두 번 → 한 번 처리). **건드리는 자리**: `outbox/`·소비자. **닫힘**: `ConsumerIdempotencyTest`·`DlqTest` | 28 |

### 5 — 관측·부하·다중 인스턴스

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 30 | 지표·대시보드 | Micrometer → Prometheus, Grafana 대시보드(선점 성공률·대기열 길이·응답 시간). compose 추가. **축**: `D10`. **강제 지점**: 테스트(지표 이름이 문서와 같다). **건드리는 자리**: `docker-compose.yml`, `observability/`, `docker/grafana/`. **닫힘**: `MetricNamesTest` | 13·21 |
| 31 | k6 시나리오 | 동시 1만 접속·좌석 1천 경쟁. 결과를 `doc/notes/load-1.md` 에. **축**: 측정. **강제 지점**: 없다. **건드리는 자리**: 신설 `load/`. **닫힘**: 리포트에 p95·성공 좌석 수·오류율이 있다 | 23·30 |
| 32 | `D13` 성능 목표 | 31 의 측정값으로 목표를 정한다. **축**: 측정값. **강제 지점**: 없다 — 문서. `35` 가 검증. **건드리는 자리**: 신설 `performance-goals.md`. **닫힘**: 수치 셋(p95·처리량·오류율) | 31 |
| 33 | 다중 인스턴스 | `docker compose up --scale app=3` + nginx. **첫 검사는 세션이 인스턴스를 넘어가나**(20a). 그다음 스케줄러 셋(스윕·타임아웃·종료·입장)이 Redis 락으로 하나만 도나. **축**: `D4`·ADR 0004. **강제 지점**: 테스트(세션 공유·스케줄러 단일 실행). **건드리는 자리**: `docker-compose.yml`, `docker/nginx/`, 스케줄러, `application.yml`(`forward-headers-strategy: native` + 신뢰 대역 루프백 — 안 켜면 `acted_ip` 가 프록시 IP 가 된다, `D9`). **닫힘**: `SharedSessionTest` + `SchedulerSingleRunTest` | 20a·22·30 |
| 34 | Redisson 분산락 비교 | 조건부 UPDATE vs Redisson 락 을 33 환경에서 재고 ADR 0008. **축**: 측정. **강제 지점**: 없다. **건드리는 자리**: `reservation/`, `doc/adr/0005`. **닫힘**: ADR 에 두 수치 | 33 |
| 35 | 부하 2차 | 33 환경에서 31 재실행, `D13` 대조. **축**: `D13`. **강제 지점**: 없다. **건드리는 자리**: `doc/notes/load-2.md`. **닫힘**: 목표 대비 표 | 32·33 |
| 35a | 운영 설정 | `server.shutdown=graceful`(선점 트랜잭션이 잘리지 않게), actuator `readiness`/`liveness` 그룹 + DB·Redis 인디케이터, `spring.threads.virtual.enabled` 켜기 전후를 31 시나리오로 측정. **축**: 관례(k8s 프로브) + 측정. **강제 지점**: 테스트(`/actuator/health/readiness` 가 DB 끊기면 DOWN). **건드리는 자리**: `application.yml`, `doc/notes/virtual-threads.md`. **닫힘**: `ReadinessTest` | 30 |

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
| P3 | `api-guidelines.md` 이식 | 완료 — 실물 기준 재작성. 경로 표 22줄, 오류 `type` 계약 30개(새 것 17), 열거값 소문자(DB 와 한 단어). 선점은 `/api/performances/{id}/reservations` — 관문이 회차 id 를 경로에서 읽는다 | 완료 |
| P4 | `testing-strategy.md` 이식 | 완료 — 실물 기준 재작성. 레인 셋(`measure` 는 `build` 밖), `ConcurrencyTestBase` 규칙 다섯, 「승자 하나」를 응답·DB 넷으로, 정렬 지운 대조 테스트, 만료 테스트는 지난 행을 직접 넣는다 | 완료 |
| P7 | `time-rules.md` 이식 | 완료 — 실물 기준 재작성. **시계는 DB**(`now()`), 관람일 N일 전 = KST 달력일 차(`at time zone` 없으면 0~9시 취소가 전날로), 박제 표 다섯 | 완료 |
| P8 | `security-baseline.md`·`observability-rules.md` 이식 | 완료 — 실물 기준 재작성. 선행을 23 으로 뒀었지만 근거(3·4·5·D12)가 이미 있었다. 보안: 정지 계정 타이밍, 세션 재생성, 프록시 IP 미설정(33 이 켠다), 트리거 한계(4a). 관측: 지표 이름 표 10개 | 완료 |
| P9 | `frontend-rules.md`·`screen-rules.md` 이식 | 예매·좌석도 화면 규약. **축**: 규약 + WCAG. **강제 지점**: lint 접근성 규칙. **건드리는 자리**: 두 문서. **닫힘**: `doc-lint.sh` 통과 + 머리말 없음 | 41 |
| P10 | `quality-gates.md` 이식 | detekt·CodeQL Kotlin. **축**: 규약. **강제 지점**: CI 잡 목록이 문서와 같다. **건드리는 자리**: 그 문서. **닫힘**: `doc-lint.sh` 통과 + 머리말 없음 | 46·47 |
| P11 | `stack.md`·`identifier-rules.md`·`external-references.md` 이식 | 버전표 재작성, 쇼핑 참조 삭제. **축**: 규약. **강제 지점**: `StackVersionConsistencyTest`(버전표와 빌드 파일 대조). **건드리는 자리**: 세 문서. **닫힘**: 그 테스트 초록 + 머리말 없음 | 1 |
| 48 | Railway 배포(선택) | 사용자가 켜면. **축**: 관례. **강제 지점**: 없다. **건드리는 자리**: `Dockerfile`·env. **닫힘**: 공개 URL 의 `/api/health` 200 | 38 |

## 이 계획을 고칠 때

- 완료 행은 안 고친다. 그때 그렇게 쳤다는 기록이다
- 새 행은 넷을 다 적는다. 빠지면 `doc-lint.sh` 가 빨갛다
- 청크를 흡수·분할하면 이력에 밝힌다
