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

## 누가 치나 — 모델 배정

**설계·판단 청크는 Fable, 코드 청크는 Opus.** Fable 용량이 적어 그 몫만 남긴다. 아래 표와 분할표의 `[Fable]` 표시가 같은 것이다.

| Fable 몫 | 열리는 때 |
|---|---|
| `28` 의 ADR 0007(왜 지금 Kafka 인가) — 코드는 Opus | `27` 뒤 |
| `32` = `D13` 성능 목표 | `31` 뒤 |
| `34` → **ADR 0008 완료**(조건부 UPDATE 유지. Redisson 대신 우리 Redis 락으로 쟀고 비율은 안 뒤집혔다) | `33` 뒤 |
| `P9` `D16`·`D17` 화면 규약 | `41` 뒤 |
| `P10` `D18` 품질 게이트 | `46a`·`47` 뒤 |
| `/inspection` | 묶음 서너 개마다 |
| `/wrapup` 의 독립 리뷰 처분 | 묶음마다 — 리뷰 에이전트 결과를 읽고 처분하는 판단 |

Opus 는 Fable 몫에 닿으면 멈추고 「Fable 차례」라고 적는다. 갈리는 결정이 코드 청크에서 나오면 그것도 Fable 에 묻는다(`CLAUDE.md` 「중간 — 갈리면 멈춘다」).

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
| D11 | `event-catalog.md` | 봉투·카탈로그 넷·버전·발행·소비·알림 규약 | 완료(Fable, 선행을 `18` 로 — 근거는 13~18 의 사건) |
| D12 | `queue-design.md` | 대기열 자료구조, 입장 속도·정원 등식, 토큰, 관문 범위, 이탈 | 완료(12c) |
| D13 | `performance-goals.md` | 응답 시간·처리량 목표, 측정 방법 | 완료(`32`) — `31`·`35` 의 부하가 근거. 목표가 **모양별**이다(한 대 / 같은 기계에 셋) |
| D14 | `coding-rules.md` | 계층·예외·트랜잭션·SQL·테스트. **Kotlin 으로** | 완료(`6`) |
| D15 | `naming-rules.md` | DB·Kotlin 식별자 | 완료(`6`) |
| D16 **[Fable]** | `frontend-rules.md` | 서버·클라이언트 경계, `api.ts` | 이식됨 → `P9` |
| D17 **[Fable]** | `screen-rules.md` | 화면 문구·권한 없는 버튼·오류 표시 | 이식됨 → `P9` |
| D18 **[Fable]** | `quality-gates.md` | 게이트 목록과 문턱, 리뷰 지적 처분 | 완료(`P10a`) — 머리말은 `P10` 이 지운다 |
| D19 | `stack.md` | 버전, 공식 문서, 기억으로 쓰면 틀리는 자리 | 완료(P11) |
| D20 | `seat-read-model.md` | 좌석 현황 계약, 버전·스냅샷·델타, ETag | 완료(12d) |
| D21 | `settlement-rules.md` | **정책 표 + 명세 항목.** 회차당 정산서 하나, 종료 뒤 D+7, 불변식 셋 | 완료 |

## 청크 분할표

파일 1~3개, 커밋 1개 단위다. 번호에 붙은 글자는 나중에 끼워 넣은 것이다.
**미착수 행에는 넷을 적는다** — 축, 강제 지점, 건드리는 자리, 닫힘(초록이면 닫히는 테스트 이름). 넷 다 예보라 치면서 달라질 수 있다.
**완료 행에는 결과만 적는다.** 왜 그렇게 정했나는 `PROGRESS.md` 이력이 든다.

`P` 줄(이식)은 코드 청크 사이에 끼워 친다 — **코드 청크 셋에 문서 청크 하나**를 넘지 않는다(CLAUDE.md 대전제 5).

### 0 — 토대

| # | 청크 | 무엇을 하나 | 선행 / 상태 |
|---|---|---|---|
| 0 | 저장소 뼈대 | `CLAUDE.md`·`PLAN.md`·`PROGRESS.md`·훅·스킬·스크립트·CI·compose·ADR 0001·0002·기준 문서 이식 | 완료 |
| 1 | backend 골격 | 완료 — Kotlin 2.3.21 + Boot 4.1.1 + Gradle 9.7.1, JDK 25 툴체인. `V1__baseline.sql`(DB 시간대 UTC), `GET /api/health`(앱·DB·적용 마이그레이션 수). 테스트 두 레인(`test` 는 `db` 태그 제외, `integrationTest` 는 포함, `build` 가 둘 다). `PostgresTestBase`(Testcontainers postgres:17, `@ServiceConnection`). `HealthControllerTest` 가 마이그레이션 수를 1 로 고정 — 새 `V*` 마다 올린다 | 완료 |
| 2 | CI 첫 초록 | 완료 — `backend/gradlew` 실행 비트. 네 잡(backend·frontend·secrets·docs) 초록, backend 1분 43초. `main` 가지 보호: 네 잡 필수, force push·삭제 금지, 관리자는 안 막는다(CI 설정이 깨졌을 때 저장소가 잠기지 않게) | 완료 |
| 3 | 계정·인증 포팅 | 완료 — `V2__account.sql`(`account` 한 테이블, 역할 컬럼 셋 check, 살아있는 행 check, `lower(email)` 부분 유일 인덱스, 길이 check 254·50). `error/`(ErrorCode 10개 `tag:` type, TicketException, ProblemFactory, ProblemEntryPoint 401+`WWW-Authenticate: Session`, ApiExceptionHandler 가 프레임워크 오류도 우리 type 으로). `auth/`(SecurityConfig 세션+CSRF 쿠키, TicketUserDetailsService 해시 소거, AuthController signup/login/logout, SignupService, Password 15~64 ASCII, EmailAddress 254, 절대 만료 12h 필터, 생존 필터). `account/MeController`. `ArchitectureTest` 3규칙(순환 금지·웹 애너테이션은 Controller 만·ProblemDetail 은 error 만). 로그인 실패 카운터는 `3a` 로 뺐다 | 완료 |
| 3a | 로그인 실패 카운터 | ProjectShop `LoginAttemptService`(이메일+IP 5회 → 차단, 문구는 일반 실패와 같게). Redis 가 필요해서 `21` 뒤로. **축**: `D9`(OWASP 계정 열거·무차별 대입). **강제 지점**: 테스트(5회 뒤 맞는 비밀번호도 401, 본문이 일반 실패와 같다). **건드리는 자리**: 신설 `auth/LoginAttemptService`, `AuthController` 수정. **닫힘**: `LoginAttemptTest.blocked_looks_like_ordinary_failure` — 완료(`auth/LoginAttemptService`: 이메일+IP 5회 → 15분, 첫 실패에만 TTL. 대조 앞에서 막는다. ProjectShop 의 메모리 폴백은 안 가져왔다 — 세션도 Redis 라 Redis 가 죽으면 로그인 자체가 안 된다. 테스트 다섯) | 완료 |
| 3b | 비밀번호 블록리스트 | NIST SP 800-63B 의 SHALL — 유출·사전 단어 목록과 대조해 거절. 목록 출처(HIBP 오프라인 상위 N 또는 자체 파일)와 검사 시점(가입·변경)을 정한다. **축**: 표준(NIST 800-63B §3.1.1.2). **강제 지점**: 앱 검증(`Password` 옆 커스텀 제약) + 테스트. DB 로는 못 내린다. **건드리는 자리**: 신설 `auth/PasswordBlocklist`, `Password` 수정, 목록 파일. **닫힘**: `PasswordBlocklistTest.common_password_rejected` — 완료(`auth/PasswordBlocklist` + `@NoCommonPassword` 를 `@Password` 에 품었다. 목록은 SecLists 10k 오프라인 파일. **길이 15자가 이미 그 목록을 막아서**(15자 초과 항목이 하나뿐) 실제로 잡는 것은 늘려 만든 것 — 단어 반복·한 글자·연속·서비스 이름·이메일 로컬파트. 거절 이유는 응답에 안 싣는다. 테스트 여섯) | 완료 |
| 4a | 감사 로그 권한 분리 | 앱이 쓰는 역할에서 `audit_log` 의 `update`·`delete`·`truncate` 를 회수하고 파기 배치만 다른 역할로 돌린다. **트리거는 테이블 주인이 끌 수 있어서 앱과 같은 역할로는 못 막는다**(`V3` 주석). **축**: `D9`. **강제 지점**: 권한(강제 지점 1위에 가장 가깝다). **건드리는 자리**: 새 `V`, 배포 문서. **닫힘**: `AuditRoleTest.app_role_cannot_delete` — 완료(`V20`: `ticket_app`·`ticket_purge` 역할, 앱 역할에서 `audit_log` 의 `update`·`delete`·`truncate` 회수. 앱은 `connection-init-sql` 로 역할을 입고 Flyway 는 자기 연결로 DDL 을 돈다. 파기 배치만 그 트랜잭션 안에서 `set local role ticket_purge`. 테스트 다섯 + `AuditImmutabilityTest` 를 두 층으로. **접속 계정 분리는 36·38**) | 완료 |
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
| 10a | 좌석 읽기 모델 Redis 화 | `D20` 의 Redis 절반 — 커밋 뒤 `afterCommit` 에서 `INCR seat:ver`·`XADD seat:log`(선점·해제·확정·스윕·취소), 버전 키 스냅샷(TTL 10s), `GET /api/performances/{id}/seats/changes?since=` 델타와 410 `seat-changes-expired`. 10 이 DB 대체값(`max(updated_at)`)으로 시작한 `SeatQuery.version` 을 Redis 로 바꾼다. **축**: `D20`·`D4` 「트랜잭션 경계」(커밋 뒤). **강제 지점**: 테스트(롤백된 선점이 화면에 안 보인다·`since` 가 잘리면 410). **건드리는 자리**: `event/SeatQuery`·`SeatController`, 신설 `event/SeatVersion*`, 쓰기 서비스 넷에 `afterCommit` 한 줄씩. **닫힘**: `SeatChangesTest` — 완료(`event/SeatVersions` 신설: `INCR`+`XADD`(스트림 id = 버전)·스냅샷 TTL 10초·`changesSince`. 키가 없으면 DB `max(updated_at)` 으로 씨를 뿌린다. `GET …/seats/changes?since=` 와 410 `seat-changes-expired`. 좌석을 바꾸는 **일곱** 자리가 `returning` 으로 알린다. 테스트 여섯) | 완료 |
| 11 | 기획사 API | 완료 — `/api/organizer` 넷: 공연 등록(등급·구역 매핑을 한 트랜잭션에 — 등급 없는 공연은 회차를 못 연다)·회차 등록·오픈·취소. **역할은 경로 규칙**(`SecurityConfig` 의 `/api/organizer/**` → `hasAuthority`)이 필터에서 막고 `ProblemAccessDeniedHandler` 가 `organizer-forbidden` 이름을 붙인다. **소속은 `OrganizerMembership` 이 보고 남의 것은 404**(`D5`). 기획사는 요청이 지명한다(사용자 선택 — `organizer_member` 가 다대다다). 취소 입구만 `payment` 패키지(의존 방향). `ErrorCode` 넷 추가. **마무리 4차가 `V18__performance_hall_slot.sql` 을 열었다** — 같은 홀·같은 시각을 막는 제약이 없는데 `catch (DuplicateKeyException)` 이 그것을 잡는 척하고 있었다. 닫힘: `OrganizerScopeTest`(열) | 완료 |
| 12 | 시드·데모 | 완료 — `demo/DemoSeeder`(`local` 의 `ticket.demo.enabled`). **마이그레이션이 아니라 기동 러너**(사용자 선택) — 시드가 `OrganizerEventService`·`PerformanceOpenService` 를 밟아 그 경로가 도는지도 확인되고 Flyway 이력이 안 더럽혀진다. 대신 멱등을 스스로 든다(표식 기획사). 공연장 1·홀 2·좌석 2천·공연 2·회차 4(둘은 열고 둘은 `draft`)·계정 6. 공연장/좌석만 SQL(만드는 입구가 없다). `D14`·`stack.md`·backend CLAUDE.md 갱신. 닫힘: `SeedBootTest`(셋) | 완료 |
| 12a | `D3` 예매 상태기계 | 완료 — `state-machines.md` 재작성(P6 흡수). `held→paying→reserved→cancelled`, `→expired`. **`paying` 이 스윕과 승인의 경합을 푼다**(ADR 0004). 좌석 상태는 예매의 투영. 회차에 `cancelled` 추가. 자동 전이 셋(스윕·타임아웃·종료) 전부 멱등 | 완료 |
| 12b | `D4` 동시성 규약 | 완료 — `concurrency-rules.md` 재작성(P5 흡수). Read Committed 로 충분한 근거(§13.2.1 WHERE 재평가), 다중 행은 **id 오름차순 `for update` 먼저**(교착 방지), 문장 순서(예매 행 → 잠금 → 조건부 UPDATE → 행 수 비교), `reservation_seat`=기록·`reservation_id`=포인터, 합계 불변식은 지연 제약 트리거, 멱등키(선점·결제 필수), 재시도는 40P01 만 1회 | 완료 |
| 12c | `D12` 대기열 설계 | 완료 — `queue-design.md` 신설. 등식(R ≤ 풀/지연), 키 다섯, `ZADD NX` 로 순번 유지, Lua 원자 입장, 무작위 토큰(반납 가능), **관문은 선점만**(ADR 0004), 선점 성공 시 반납, Redis 죽으면 관문 닫힘(503) | 완료 |
| 12d | `D20` 좌석 읽기 모델 | 완료 — `seat-read-model.md` 신설 + ADR 0005. 커밋 뒤 `INCR` 버전, 버전 키 스냅샷(TTL 10s), STREAM 변경 로그, `ETag`/304, `?since=` 델타·410. DB 는 정하는 일만, Redis 는 보여주는 일만 | 완료 |
| 12e | 세션 저장소 결정 | 완료 — ADR 0004 에 흡수. Spring Session Redis. JWT(즉시 취소 불가)·sticky(문제를 숨긴다) 버림 | 완료 |
| 12f | 동시성 테스트 바탕 | 완료 — `ConcurrencyTestBase`(`@Transactional` 없음, 애너테이션은 `PostgresTestBase` 와 동일해 컨텍스트 하나). `runConcurrently(n)` 이 출발선을 맞추고 스레드별 `Result` 를 돌려준다. 정리는 `concurrency-` 접두(계정·기획사·공연장)를 앞뒤 한 트랜잭션에서 `restrict` 순서로. 닫힘: `ConcurrencyTestBaseTest` 둘(스레드 100 커밋 100, 접두만 지움) | 완료 |

### 2 — 예매 동시성 (핵심)

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 13 | 좌석 선점 (단일 인스턴스) | 완료 — `POST /api/performances/{id}/reservations`·`GET /api/reservations/{id}`. `SeatHoldService`(D4 순서 그대로, 한 트랜잭션)·`ReservationService`(멱등 + 40P01 1회 재시도)·`IdempotencyService`(ProjectShop 포팅, insert 가 락)·`ReservationQuery`. `V7`: 표 셋 + 포인터 외래키 + 전이·출생 트리거 + 합계·좌석 유무·응답 유무 지연 트리거. `ErrorCode` 7 추가, `TicketException.properties`. `ArchitectureTest` 규칙 둘(LocalDateTime·@Valid). 닫힘: `SeatHoldConcurrencyTest`(승자 하나 + 잠금 순서 대조)·`ReservationSeatConsistencyTest`·`ReservationHoldTest` | 완료 |
| 13a | 예매 교차 표 제약 | 완료 — `V12` 트리거 둘(즉시, `before insert`). `payment_amount_matches_reservation`(결제 금액 = 예매 합계)·`reservation_seat_same_performance`(좌석 기록의 회차 = 예매의 회차). 지연이 아닌 이유는 삽입 순간 양쪽 행이 이미 있어서다. 닫힘: `PaymentConfirmTest.payment_amount_must_equal_the_reservation_total`·`ReservationSeatConsistencyTest.seat_of_another_performance_cannot_be_recorded` | 완료 |
| 14 | 선점 만료 스윕 | 완료 — `HoldSweeper.sweep()` 30초(`SchedulingConfig` 가 스위치). 예매 `held→expired`(`held_until < now()`) 뒤 그 예매 id 로 좌석 `held→available`. 둘 다 조건부라 멱등. `paying` 은 `paying_until` 로만 걸린다(16 이 같은 문장에 더했다). 만료마다 `reservation.expired` 감사. 닫힘: `HoldSweeperTest`(넷 — 만료 해제·`does_not_release_confirmed`·살아있는 것·멱등) | 완료 |
| 15 | 1인 N매·중복 선점 제한 | 완료 — `V8` `reservation_live_hold_idx`(계정·회차, `held`·`paying` 만). `SeatHoldService`: `on conflict do nothing` → 409 `duplicate-hold`(기존 예매 id), 살아있는 예매 좌석 수 + 요청 > 4 → 422 `over-limit`. 앱 검증이 경합에 안전한 근거는 그 인덱스(둘째 insert 가 첫째 커밋을 기다린다). 닫힘: `ReservationLimitTest`(다섯 — 중복·인덱스 자체·확정분 합산·만료분 제외·동시 10) | 완료 |
| 16 | 결제 포팅 + 확정 | 완료 — `POST /api/reservations/{id}/payments`. `PaymentService`(① → PG 밖 → ③ 멱등 트랜잭션)·`PaymentTransitionService`(`startPaying`·`settle` 조건부 UPDATE)·`MockPaymentGateway`(카드 하나, `inquire` 추가 — 뒷 4자리 0000 거절·0001 무응답·0002 지연)·`PaymentController`. 무응답은 재시도 없이 상태 조회(`D4`). `V9` payment(승인 예매당 부분 유일, 고칠 수 없음). `HoldSweeper` 가 `paying` 타임아웃도. `IdempotencyKeys` 공용. 닫힘: `PaymentConfirmTest`(`expired_hold_cannot_confirm`·`sweeper_does_not_touch_paying` 포함 열)·`PaymentFlowTest`(열) | 완료 |
| 16a | 판매 마감·자동 종료 | 완료 — `V14`: `sales_close_at` not null(기존 행 backfill), `performance_sales_window_check`(열림 < 마감 < 시작), `before insert` 트리거가 기본값 `starts_at - 1h`(사용자 선택 — 입구가 늘어도 빠뜨릴 자리가 없다), 마감 지난 `open` 만 드는 부분 인덱스. `PerformanceCloser`(1분, 고르기는 트랜잭션 밖)·`PerformanceCloseService`(회차마다 트랜잭션 — 사용자 선택). `held`·`paying` → `expired` + 좌석 반납, `reserved` 는 안 건드린다. `performance.closed` 사건을 같은 트랜잭션에. **`event` 가 아니라 `reservation` 패키지다** — 의존 방향(`D14`)이 그렇고 `ArchitectureTest` 가 잡았다. 대기열 키 삭제는 `21`·`24` 가 든다. 닫힘: `PerformanceCloseTest`(여덟 — `held_seats_expire_on_close` 포함) | 완료 |
| 17 | 취소·환불 + `D6` | 완료 — `POST /api/reservations/{id}/cancel`(200). `RefundTransitionService`(잠금 → 구간 행 선택 → 박제 환불 행 → `reserved→cancelled` → 좌석 해제)·`RefundService`(PG 환불 밖, 키 = 환불 id → `done`)·`RefundPolicy`(순수: KST 달력일 차·half-up)·`RefundController`(payment 패키지 — 의존 방향). `V10`: `refund_fee_tier` 시작 4행(`effective_at` 판), `refund`(결제당 하나, 비관객 사유 전액 check, 등식 지연 트리거). 닫힘: `RefundPolicyTest`(자정 앞뒤 1초, UTC 입력)·`ReservationCancelTest`(아홉)·`RefundInvariantTest` | 완료 |
| 17a | 회차 취소 | 완료 — `V17`(status check·전이 트리거에 `cancelled`, `open` 에서만). `payment/PerformanceCancelService`(한 트랜잭션: 회차·예매·좌석·전액 환불 행·사건). **`17b` ⓐ 를 흡수했다** — `payment/RefundSweeper`(10초, `requested` 를 같은 키로 PG 에). PG 를 취소 트랜잭션에서 안 부른다(`D4` — 예매가 수백이면 호출도 수백). 소비자 둘을 채웠다: 알림은 예매자 전원(사건 하나·수신자 여럿), 정산은 취소도 예약(항목 전부 0). 입구는 `11` 로(`D5` 갱신). 닫힘: `PerformanceCancelTest`(여덟 — 마무리 4차가 `a_rollback_takes_the_event_with_it` 을 더했다) + `SettlementAggregateTest.cancelled_performance_settles_to_zero` | 완료 |
| 17b | 결제 지연 환불 행 | ⓐ(`requested` 되살리기)는 `17a` 가 `RefundSweeper` 로 흡수했다. 남은 것은 ⓑ `payment.late` 감사만 남은 승인 결제(예매가 `reserved` 가 아닌 `approved` 결제)에 전액 환불 행(`reason = payment_late`, 율 0)을 만든다 — 16 이 PG 취소만 보내고 행은 안 만들었다. **축**: `D4`(재시도 — PG 가 같은 키에 같은 답을 준다). **강제 지점**: 조건부 INSERT(`not exists`) + 결제당 하나 유일 제약 + 테스트 — **예보는 조건부 UPDATE 였는데 실제로는 행을 만드는 일이라 INSERT 다**(마무리 5차 대조). 완료(`RefundTransitionService.queueLatePayments`. 대상을 `expired` 예매로 좁혔다 — `reserved 가 아닌 것` 전부면 취소가 중간에 실패한 건이 `payment_late` 로 박힌다. `RefundSweeper` 가 같은 회에 만들고 보낸다. 닫힘: `RefundSweeperTest` 셋). **건드리는 자리**: `payment/RefundSweeper`, `HoldSweeper` 와 같은 스케줄러. **닫힘**: `RefundSweeperTest` | 완료 |
| 18 | 발권 | 완료 — `TicketService.issue`(승인 트랜잭션 안, `PaymentTransitionService.confirm` 이 부른다). 번호 `YYYYMMDD-XXXXXX`(KST 날짜 + `SecureRandom` 6자, 32자 집합), 충돌은 `on conflict do nothing` 0행으로 재추첨 3회. `V11` ticket(좌석당 하나·번호 유일·형식 check·불변). `GET /api/reservations/{id}/tickets`(본인, 발권 전 `[]`)·`TicketQuery`. 닫힘: `TicketIssueTest`(여섯) | 완료 |
| 19 | 동시성 계측 | 완료 — `LockComparisonTest`(`measure` 태그, 스크래치 표, 100·1000 스레드 × 셋), gradle `measure` 레인(`build` 밖, 늘 다시 돈다), `doc/notes/lock-comparison.md`(두 실행 + 기계 사양), **ADR 0006**: 1000 스레드에서 조건부 1 : 낙관 1.4 : 비관 4.3(p95 는 1 : 1.5 : 7.5), 셋 다 승자 하나 — 조건부 UPDATE 유지, `version` 컬럼 안 둔다. `D4` 낙관락 행 닫음, ADR 0003 포인터 | 완료 |
| 20 | ~~이식 P5·P6~~ | 12a·12b 가 흡수했다 — 두 문서를 코드보다 먼저 썼다(ADR 0004). `StateMachineDocTest`(전이표와 문서 대조)는 `13` 이 세운다고 적어 뒀는데 안 세웠고 `I3-1` 이 세웠다(점검 3차) | 완료 |

### 3 — 대기열

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 21 | 대기열 진입·순번 **[종료 시 키 삭제도]** | 완료 — `queue/` 신설 셋(`QueueKeys`·`QueueService`·`QueueController`). `POST`·`GET /api/queue/{performanceId}` → `{rank, eta_seconds}`, 200(자원이 아니라 동작이라 `Location` 이 없다). 진입은 Lua 한 번 — `TIME`(서버 시계)·`ZADD NX`(재진입이 순번 유지)·`ZRANK` 가 원자다. `eta = 올림(rank / 20)`(ADR 0003 의 R). 종료 시 키 삭제는 `PerformanceCloser`·`OrganizerCancelController` 가 **커밋 뒤에** 부른다. `ErrorCode` 둘 추가(`queue-closed` 410·`not-in-queue` 404, `D5` 에 줄). `D12` 의 「키 다섯 삭제」를 셋으로 고쳤다 — 토큰 키는 회차로 못 훑어 TTL 이 지운다. 닫힘: `QueueRankTest`(일곱 — 순번 단조·재진입·eta 올림·404·410·종료가 줄을 걷는다·응답 계약) | 완료 |
| 22 | 입장 스케줄러·활성 토큰 | 완료 — `AdmissionService`(Lua 하나: 만료 정리 → 빈자리 = C − 활성 → `ZPOPMIN` → 토큰·정원 저장)·`AdmissionScheduler`(5초, 판매 중 회차를 훑는다). 수치는 C 2,000·배치 100·TTL 10분(ADR 0003), R = 20/초는 `QueueService` 가 이 값을 쓴다 — 들이는 쪽과 예상 대기가 갈리면 화면이 거짓말을 한다. 토큰은 무작위 32바이트 + Redis TTL(서명이 아니라 저장 — 선점 성공 때 반납해야 정원이 돈다). 폴링 응답이 `state=waiting|admitted` 로 갈린다(**사용자 선택**), 입장한 사람은 다시 줄에 안 선다. `D12` 응답 모양을 고쳤다. 닫힘: `AdmissionTest`(여덟 — 순서·토큰 내용·TTL·정원 만석·만료가 정원 반납·배치 상한·재진입·폴링이 토큰을 건넨다) | 완료 |
| 23 | 대기열 관문 | 완료 — `QueueGateFilter`(`POST /api/performances/{id}/reservations` 에만). 없음·만료·위조는 다 429 `admission-required`(본문에 순번), 남의 계정·다른 회차는 403 `admission-mismatch`, Redis 가 죽으면 503 `queue-unavailable`(관문을 닫는다). 성공한 선점(201)만 토큰을 반납하고 실패는 안 뺏는다. **판매 중이 아닌 회차는 안 막는다** — 토큰을 받을 길이 없는데 429 면 거짓말이라 서비스가 409·410 으로 답하게 넘긴다(DB 는 토큰이 없을 때만 본다). 곁가지로 `error/ProblemWriter`(필터가 RFC 9457 을 쓰는 자리)·`auth/SecuredApiFilter`(의존 역전 — `auth ↔ queue` 순환을 `ArchitectureTest` 가 잡았다). 닫힘: `QueueGateTest`(일곱) | 완료 |
| 24 | 이탈·새로고침 | 완료 — 23 이 흡수했다. 폴링이 하트비트를 겸하고 진입도 첫 자국을 남긴다(안 남기면 한 번도 폴링 안 한 사람이 안 걷힌다). `QueueSweeper`(30초)가 90초 끊긴 사람을 줄과 `seen` 에서 **한 Lua 로 같이** 뺀다 — 하나만 지우면 유령이 남는다. `DELETE /api/queue/{id}` 는 줄에서 빼고 토큰을 반납한다(204, 줄에 없어도 204). 새로고침이 순번을 지키는 것은 21 의 `ZADD NX`. 닫힘: `QueueHeartbeatTest`(다섯) | 완료 |
| 23a | 관문과 멱등 재시도 | 성공한 선점이 토큰을 반납해서(23), **응답을 못 받은 클라이언트가 같은 멱등키로 재시도하면 관문이 429 로 끊는다** — `D4` 「멱등키」가 약속한 「저장된 본문을 그대로 돌려준다」가 관문 뒤에 있어 안 닿는다(마무리 5차 독립 리뷰가 잡았다). **축**: `D4`·`D12`. **강제 지점**: 테스트. **건드리는 자리**: `queue/QueueGateFilter`·`AdmissionService`, `D12` 「토큰」. **닫힘**: `QueueGateTest.a_retry_with_the_same_key_replays` — 완료(사용자가 ⓐ 를 골랐다. `releaseAfterHold` 를 갈라 활성 집합에서만 빼고 **토큰 키는 45초로 줄이기만 한다**. Lua 한 번에 `ZREM`+`DEL`+조건부 `PEXPIRE` — 남은 TTL 이 유예보다 클 때만 줄여서 재시도가 창을 뒤로 못 민다. `release`(줄 이탈, 24)는 그대로 지운다. ⓑ 를 버린 이유는 대기열 층이 예매 층 저장소를 읽게 되고 관문을 지나는 모든 요청에 조회가 하나 늘어서다. 테스트 셋: 재시도가 같은 본문을 받는다, 반납 뒤 TTL 이 45초 이하다, 창 안에서도 둘째 좌석은 409) | 완료 |
| 20a | 세션 저장소 Redis | 완료 — `spring-boot-starter-session-data-redis`, `spring.session.data.redis.repository-type: indexed`(계정으로 세션을 찾아야 `5a`·`5b` 가 된다). `SessionRegistryImpl` → `SpringSessionBackedSessionRegistry`, `RegisterSessionAuthenticationStrategy`·`HttpSessionEventPublisher` 를 걷었다(등록이 빈 함수고 정리는 Redis 가 한다). **쿠키 직렬화기를 코드로 내렸다**(`SecurityConfig.cookieSerializer`) — Boot 자동설정이 MockMvc 컨텍스트를 war 배포로 보고 이름을 `SESSION` 으로 떨궈서 테스트와 운영이 갈렸다. `PostgresTestBase` 가 Redis 컨테이너(`@ServiceConnection(name = "redis")`)와 `@BeforeEach flushDb()` 를 든다. `LoginFlowTest` 넷을 쿠키·저장소 기반으로 고쳤다. 닫힘: `SharedSessionTest`(둘 — 로그인이 넘어간다·로그아웃이 같이 끊는다) | 완료 |
| 5a | 탈퇴·파기 | `DELETE /api/me`(세션 전부 끊기·`deleted_at`), 30일 뒤 파기 배치(이메일·이름·해시 null, 동의 cascade 는 물리 삭제 때), 감사 3년 파기. **축**: `D9` + 개인정보보호법 제21조. **강제 지점**: 트리거(`deleted_at` 계정 로그인 불가 — 있음) + 배치 테스트. **건드리는 자리**: `account/WithdrawalService`·`AccountPurgeBatch`. **닫힘**: `AccountPurgeTest.pii_nulled_after_grace` — 완료(`WithdrawalService`·`AccountPurgeBatch`·`DELETE /api/me`. 탈퇴는 `deleted_at` 만 찍고 커밋 뒤에 세션을 끊는다. 파기는 30일 뒤 이메일·이름·해시 null + 감사 3년 삭제. **행은 안 지운다** — 예매·결제가 외래키라 산 기록이 끌려간다. 마이그레이션이 필요 없었다: `V2` 가 이미 널 허용 + 「살아 있으면 값이 있다」 체크를 들고 있다. 테스트 여섯) | 완료 |
| 5b | 관리자 정지·해제 | `admin` 전용 `POST /api/admin/accounts/{id}/suspend`, 정지 즉시 그 계정 세션 전부 만료, 감사. **축**: `D9`. **강제 지점**: 테스트(정지 직후 다른 컨텍스트의 세션이 401). **건드리는 자리**: `account/AdminController`. **닫힘**: `SuspendTest.other_session_cut_immediately` — 완료(`AccountSuspensionService`·`AdminAccountController`·`auth/AccountSessions`. 정지·해제는 조건부 UPDATE 고 0행이면 없는 계정(404)과 이미 그 상태(409)를 가른다. 세션 끊기는 커밋 뒤, 응답에 끊은 세션 수를 싣는다. `ADMIN_PREFIX` 로 역할을 경로가 본다. 테스트 다섯) | 완료 |

### 4 — 비동기 분리

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 25 | 아웃박스 | 완료 — `V13__outbox.sql`(봉투 열 아홉, `event_id` 유일, 미발행 부분 인덱스, `published_at` 말고는 못 고치는 트리거). `OutboxWriter.append`(`Propagation.MANDATORY` — 트랜잭션 없이 못 쓴다)·`OutboxRelay`(1초 폴링, `for update skip locked`, Spring 이벤트로 발행 뒤 표시)·`EventType`/`AggregateType`. 확정·취소 트랜잭션에 사건 둘 배선(`performance.*` 는 16a·17a 가 낸다). 닫힘: `OutboxAtomicityTest`(커밋 레인 다섯)·`OutboxRelayTest`(롤백 레인 넷)·`EventCatalogTest`(빠른 레인 — enum ↔ `D11` 표 ↔ check) | 완료 |
| 25a | 아웃박스 청소 | 완료 — `OutboxCleaner`(1시간). `published_at` 이 **7일** 지난 것만 지운다(소비자가 표를 다시 읽어 재발행 요구가 없고, 한 주면 「어제 그 알림이 왜 안 갔나」를 덮는다). 기준 시각은 DB. 안 나간 봉투는 안 건드린다 — 지우면 사건이 조용히 사라진다(그 자리는 29 의 DLQ). `D2` 수명표에 기간을 적었다. 닫힘: `OutboxCleanerTest`(셋 — 경계 하루는 남긴다·두 번째 회는 0건) | 완료 |
| 26 | 알림 포팅 (소비자 1) | 완료 — `V15__notification.sql`(`(event_id, account_id)` 유일 = 멱등, 본문 불변 트리거, 미발송 부분 인덱스). `ReservationNotificationListener`(예외 삼킴)·`NotificationStore`(`record` 는 `REQUIRES_NEW` — 소비자가 릴레이를 안 멈춘다)·`NotificationSweeper`(5초, 발송은 트랜잭션 밖)·`MockNotificationSender`(ProjectShop 포팅, `@bounce.invalid` 는 실패)·`NotificationTemplates`(존댓말, 이름 안 부른다). 확정·취소 둘. 회차 취소는 17a. 닫힘: `NotificationIdempotencyTest`(커밋 레인 일곱) | 완료 |
| 26a | 무엇이 물렀나 | 회차 취소 알림이 **관객이 먼저 무른 예매와 안 갈린다**(마무리 4차 독립 리뷰). 둘 다 `reservation.status = 'cancelled'` 라 수신자 질의가 같이 집고, 그쪽은 수수료를 뗀 부분 환불인데 문구가 「전액·수수료 없음」으로 나간다. 예매에 「무엇이 물렀나」를 남겨 가른다 — `cancelled_by`(관객·기획사·만료) 한 열이면 알림·정산·`D21` 조회가 같이 쓴다. **축**: `D2`(열 추가) + `D11`(소비자가 표를 읽는다). **강제 지점**: 전이 트리거가 `cancelled` 로 갈 때 그 열을 요구한다 + 테스트. **건드리는 자리**: `V18`, `reservation/`·`payment/` 의 취소 자리 넷, `notification/NotificationStore`. **닫힘**: `PerformanceCancelTest.an_early_canceller_is_not_told_it_was_full` — 완료(`V19__cancelled_by.sql`, `CancelledBy` 열거, 다섯 자리가 쓴다: 관객 취소·회차 취소·선점 스윕·판매 마감·결제 타임아웃. 전이 트리거가 `cancelled`·`expired` 에서 이 열을 요구한다. 알림은 `organizer` 인 사람에게만. `D3` 에 전이 규칙을 적었다. 테스트 둘을 더했다) | 완료 |
| 27 | 정산 집계 (소비자 2) | 완료 — `V16`: `settlement_policy`(기본 행 시드, `nulls not distinct` 유일)·`settlement`(회차당 하나, `scheduled → pending`, `settle_at` 박제)·`settlement_line`(부호 있음, 종류당 하나)·`performance.settlement_policy_id`(오픈 때 박제). `SettlementListener`(둘째 소비자)·`SettlementStore`(예약 `REQUIRES_NEW` + 집계)·`SettlementSweeper`(1시간)·`SettlementPolicyQuery`(`effective_at` 최신, 기획사 행이 기본을 이긴다). 합계 = 항목 합은 지연 트리거. 조회·전이 API 는 45 로 미뤘다(`D21` 갱신). 닫힘: `SettlementAggregateTest`(아홉 — `sale_equals_reserved_seat_prices` 포함) | 완료 |
| 28 | Kafka 도입 **[ADR 0007 완료]** | 완료 — ADR 0007(왜 지금인가·왜 Kafka 인가·토픽을 어떻게 가르나). **집합체 타입마다 토픽 하나**(`ticket.reservation`·`ticket.performance`), 키 = `aggregate_id`, 그룹은 소비자마다. `outbox/EventTopics`·릴레이가 Kafka 로(보내고 나서 표시), 소비자 둘을 `@KafkaListener` 로. compose 에 `apache/kafka:4.3.1`(KRaft, 로컬 9094 — 9092 는 ProjectShop). 테스트: `KafkaRelayTest`(진짜 브로커) + 소비자 행동은 `PendingEvents` 로 브로커를 건너뛰고 직접(소비가 다른 트랜잭션이라 롤백 레인이 못 본다) + `Waits`. 실물로 구매 한 번을 끝까지 밟아 소비자가 움직인 것을 봤다 | 완료 |
| 29 | 재시도·DLQ·멱등 소비 | 완료 — `ConsumerErrorHandling`(2초×3회 → `<토픽>.DLT`, 오프셋은 보낸 뒤 넘어간다), 소비자 둘이 **던진다**(삼키면 사건이 조용히 사라진다), `KafkaTopicsConfig`(파티션 3 — 자동 생성은 1개였다). 테스트: `DlqTest`(깨진 메시지를 그대로, 막힌 뒤 것도 도착하나)·`ConsumerIdempotencyTest`(같은 `event_id` 두 번 → 알림 하나). 원래 설명: **축**: 표준(at-least-once). **강제 지점**: 테스트(같은 이벤트 두 번 → 한 번 처리). **건드리는 자리**: `outbox/`·소비자. **닫힘**: `ConsumerIdempotencyTest`·`DlqTest` | 완료 |

### 5 — 관측·부하·다중 인스턴스

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 30 | 지표·대시보드 | Micrometer → Prometheus, Grafana 대시보드(선점 성공률·대기열 길이·응답 시간). compose 추가. **축**: `D10`. **강제 지점**: 테스트(지표 이름이 문서와 같다). **건드리는 자리**: `docker-compose.yml`, `observability/`, `docker/grafana/`. **닫힘**: `MetricNamesTest` — 완료(`observability/TicketMetrics` 에 이름 열하나, 여덟 자리가 기록한다. `MetricNamesTest` 가 `D10` 표와 대조하고 **부르는 자리가 있는지**도 본다. compose 에 `prometheus` v3.1.0·`grafana` 11.5.0, 데이터 소스·대시보드는 파일로 심는다. 패널 여섯. 입장 Lua 가 기다린 시간을 같이 돌려주게 고쳤다) | 완료 |
| 31 | k6 시나리오 | 동시 1만 접속·좌석 1천 경쟁. 결과를 `doc/notes/load-1.md` 에. **축**: 측정. **강제 지점**: 없다. **건드리는 자리**: 신설 `load/`. **닫힘**: 리포트에 p95·성공 좌석 수·오류율이 있다 — 완료(`load/seat-rush.js`·`prepare.sql`·`README.md`, `doc/notes/load-1.md`. A: 100 VU 선점 p95 47ms·HTTP p95 145ms. B: 300 VU 선점 p95 290ms·HTTP p95 743ms·565 req/s, 둘 다 5xx 0. **대기열이 의도대로 평평하게 만들었다** — B 의 입장 대기 p95 4초. 1만 VU 는 k6 를 다른 기계로 빼야 한다) | 완료 |
| 32 | `D13` 성능 목표 | 완료 — `performance-goals.md` 신설(Fable 이 없어 Opus 가 받았다, 사용자 지시). 목표 일곱: 선점 p95 350ms(동시 300)·100ms(동시 100), HTTP p95 900ms, 처리량 450 req/s, **5xx 0**, 대기열 대기는 절대값이 아니라 **등식 대비**(2 × 순번÷R), 스냅샷 생성은 판 변경 횟수 이하. 「이 구성에서 이만큼은 나와야 한다」의 **바닥**으로 뒀다 — 최대를 목표로 두면 기계가 바뀔 때마다 흔들려 회귀를 못 본다. 못 맞췄을 때 먼저 볼 자리와 다시 정하는 조건 셋도 적었다 | 완료 |
| 33 | 다중 인스턴스 | `docker compose up --scale app=3` + nginx. **첫 검사는 세션이 인스턴스를 넘어가나**(20a). 그다음 스케줄러 셋(스윕·타임아웃·종료·입장)이 Redis 락으로 하나만 도나. **축**: `D4`·ADR 0004. **강제 지점**: 테스트(세션 공유·스케줄러 단일 실행). **건드리는 자리**: `docker-compose.yml`, `docker/nginx/`, 스케줄러, `application.yml`(`forward-headers-strategy: native` + 신뢰 대역 루프백 — 안 켜면 `acted_ip` 가 프록시 IP 가 된다, `D9`). **닫힘**: `SharedSessionTest` + `SchedulerSingleRunTest` — 완료(`SchedulerLock`: `SET NX PX` + 내 토큰일 때만 지우는 Lua 해제, **Redisson 안 들였다**(사용자 선택, ADR 0003 고침). 스케줄러 넷의 `@Scheduled` 입구만 감쌌다. `backend/Dockerfile`(두 단계·비루트)·compose `app`(이름 없음)·`nginx`(sticky 없음)·`forward-headers-strategy: native`. `--scale app=3` 으로 띄워 nginx 뒤 여섯 요청이 같은 쿠키로 전부 200 인 것을 봤다) | 완료 |
| 34 | 좌석 분산락 비교 **[완료 — ADR 0008]** | 조건부 UPDATE vs **Redis 분산락**(`SchedulerLock` 과 같은 `SET NX`)을 33 환경에서 재고 ADR 0008. **33 이 Redisson 을 안 들이기로 해서**(사용자 선택) 비교 대상이 바뀌었다 — 재는 질문은 그대로다: 좌석 하나를 두고 다툴 때 DB 조건부 UPDATE 와 Redis 락 중 무엇이 싼가. **축**: 측정. **강제 지점**: 없다. **건드리는 자리**: `reservation/`, `doc/adr/0005`. **닫힘**: ADR 에 두 수치 — **측정은 완료**(`LockComparisonTest` 에 넷째 방식, `doc/notes/lock-comparison-redis.md` 두 회차: 1000 스레드에서 Redis 락이 전체 1.5~2배·p50 4~6배 느리다. 비용은 DB 가 아니라 Redis 왕복 — Lettuce 가 커넥션 하나를 나눠 쓴다). **ADR 0008 을 그 숫자로 썼다**(Fable 이 없어 Opus 가 받았다 — 사용자 지시). 좌석은 DB 조건부 UPDATE 유지, Redis 락은 스케줄러 전용 | 완료 |
| 35 | 부하 2차 | 33 환경에서 31 재실행, `D13` 대조. **축**: `D13`. **강제 지점**: 예보는 「없다」였는데 **마무리 6차에 생겼다** — `ComposeContractTest`(nginx 가 매 요청에 이름을 푸나·healthcheck 도구가 이미지에 있나·`readiness` 를 보나) + `verify-fingerprint.sh` 의 backend 레인에 compose·conf·Dockerfile 을 더했다. **건드리는 자리**: `doc/notes/load-2.md`. **닫힘**: 목표 대비 표 — 완료(`load-2.md`. **대조 전에 구성이 말과 달랐다** — nginx 가 `upstream` 의 이름을 기동 때 한 번만 풀어 `--scale app=3` 이어도 **한 대가 전부 받았다**(9,174 : 0 : 0). `resolver` + 변수 `proxy_pass` 로 고쳐 2,675 : 2,632 : 2,651. 33 의 healthcheck 도 같이 고쳤다(JRE 에 `wget` 이 없다). 진짜 셋의 값은 한 대보다 나쁘다 — 선점 p95 621ms·HTTP p95 1.21s·424 rps. 기계가 하나라서다. **한 대 목표로 판정하지 않았다** — 모양이 달라 적용 대상이 아니라, 이 실측이 「셋(같은 기계)」 목표의 바닥이 됐다. 모양과 무관한 둘(5xx 0·대기열 대기)은 지켰다. **목표를 안 느슨하게 하고 모양별로 갈랐다** — `D13` 에 「한 대」·「셋(같은 기계)」 두 열. 5xx 0, 대기열 대기는 등식의 1/6. 스냅샷 비율은 스크립트가 폴링을 안 해 미측정 → `44`) | 완료 |
| 35a | 운영 설정 | `server.shutdown=graceful`(선점 트랜잭션이 잘리지 않게), actuator `readiness`/`liveness` 그룹 + DB·Redis 인디케이터, `spring.threads.virtual.enabled` 켜기 전후를 31 시나리오로 측정. **축**: 관례(k8s 프로브) + 측정. **강제 지점**: 테스트(`/actuator/health/readiness` 가 DB 끊기면 DOWN). **건드리는 자리**: `application.yml`, `doc/notes/virtual-threads.md`. **닫힘**: `ReadinessTest` — 완료(`server.shutdown: graceful` + 20초 상한, 프로브 두 그룹(readiness 만 `db`·`redis` 를 본다), `ReadinessTest` 셋. 가상 스레드는 `doc/notes/virtual-threads.md` — 300 VU 에서 선점 p95 −21%지만 처리량·HTTP p95 는 조금 나빠 **결론을 낼 차이가 아니다**. 막는 자리가 커넥션 풀 10 이라 기본은 끈 채로 둔다) | 완료 |

### 6 — k8s

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 36 | kind 클러스터 + 매니페스트 | kind 설정, app Deployment(replicas 3)·Service, Postgres·Redis StatefulSet, ConfigMap·Secret. **축**: 관례(k8s 기본 리소스). **강제 지점**: `kubectl rollout status` 스크립트. **건드리는 자리**: 신설 `k8s/`. **닫힘**: `scripts/k8s-smoke.sh` 가 `/api/health` 200. **35 가 넘긴 것**: nginx 를 변수 `proxy_pass` 로 바꾸면서 `upstream` 블록의 keepalive·죽은 대 건너뛰기를 잃었다 — Service 의 엔드포인트 관리가 그 자리를 받는다 | 33 |
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
| 46a | codeql·claude-review 워크플로 | ProjectShop 둘 이식. Kotlin 은 CodeQL `java-kotlin`. **선행을 `39` 에서 뗐다**(점검 6차) — 화면과 무관한데 `46` 한 행에 묶여 있어서 **보안 게이트가 화면을 기다리고 있었다**. **축**: `D18`. **강제 지점**: CI. **건드리는 자리**: `.github/workflows/`. **닫힘**: 두 잡 초록 + `D18` 게이트 표에 행 — 완료(`codeql.yml`·`claude-review.yml`. CodeQL 은 `actions`·`java-kotlin` 둘에 `build-mode: none` — `javascript-typescript` 는 화면이 생기는 `39` 가 켠다. 확장 팩(`JdbcClient` 싱크)은 **첫 측정 뒤에** 판단한다. 리뷰는 `--setting-sources user` 로 저장소 훅을 끄고(리뷰 세션엔 칠 청크도 커밋도 없어 Stop 훅이 리뷰를 세운다) `--disallowedTools "Task,Agent"` 로 도우미를 걷는다. **막는 것은 「이번 회차에 코멘트를 남겼나」 하나다.** `D18` 게이트 표에 두 행. **CI 초록은 push 뒤에 본다** — 로컬에서는 `js-yaml` 파싱까지만 확인했다) | 완료 |
| 46b | e2e 워크플로 | Playwright 잡. 화면이 있어야 돌 것이 있다. **축**: `D18`·`D8`. **강제 지점**: CI. **건드리는 자리**: `.github/workflows/`. **닫힘**: 잡 초록 | 42 |
| 47 | detekt | 완료 — `dev.detekt` 2.0.0-alpha.6(1.23 은 JDK 25 에서 안 돈다, `stack.md`), `backend/config/detekt/detekt.yml`, `check` 에 붙어 CI 가 같이 돈다. 첫 측정 262건 → 문턱 다섯을 근거와 함께 올리고 규칙 하나를 끄고(우리 예외 규약과 충돌) 나머지 열하나를 고쳤다. 곁가지로 `observability/Elapsed.kt`. `D18` 에 게이트·문턱·처분 행 | 완료 |
| P3 | `api-guidelines.md` 이식 | 완료 — 실물 기준 재작성. 경로 표 22줄, 오류 `type` 계약 30개(새 것 17), 열거값 소문자(DB 와 한 단어). 선점은 `/api/performances/{id}/reservations` — 관문이 회차 id 를 경로에서 읽는다 | 완료 |
| P4 | `testing-strategy.md` 이식 | 완료 — 실물 기준 재작성. 레인 셋(`measure` 는 `build` 밖), `ConcurrencyTestBase` 규칙 다섯, 「승자 하나」를 응답·DB 넷으로, 정렬 지운 대조 테스트, 만료 테스트는 지난 행을 직접 넣는다 | 완료 |
| P7 | `time-rules.md` 이식 | 완료 — 실물 기준 재작성. **시계는 DB**(`now()`), 관람일 N일 전 = KST 달력일 차(`at time zone` 없으면 0~9시 취소가 전날로), 박제 표 다섯 | 완료 |
| P8 | `security-baseline.md`·`observability-rules.md` 이식 | 완료 — 실물 기준 재작성. 선행을 23 으로 뒀었지만 근거(3·4·5·D12)가 이미 있었다. 보안: 정지 계정 타이밍, 세션 재생성, 프록시 IP 미설정(33 이 켠다), 트리거 한계(4a). 관측: 지표 이름 표 10개 | 완료 |
| P9 **[Fable]** | `frontend-rules.md`·`screen-rules.md` 이식 | 예매·좌석도 화면 규약. **축**: 규약 + WCAG. **강제 지점**: lint 접근성 규칙. **건드리는 자리**: 두 문서. **닫힘**: `doc-lint.sh` 통과 + 머리말 없음 | 41 |
| P10 **[Fable]** | `quality-gates.md` 머리말·문턱 | `P10a` 가 게이트 표를 실물로 다시 썼고 **이식 머리말과 문턱이 남았다**(점검 6차 — 겹치던 자리를 여기로 좁혔다). **축**: 규약. **강제 지점**: CI 잡 목록이 문서와 같다. **건드리는 자리**: 그 문서. **닫힘**: 머리말이 없고 문턱 행이 첫 측정값을 든다(`46a` 가 CodeQL 행을 세웠다) | 46a·47 |
| P11 | `stack.md`·`identifier-rules.md`·`external-references.md` 이식 | 완료 — 셋 다 머리말 없이 재작성. `stack.md`: 버전표를 실물(Boot 4.1.1·Kotlin 2.3.21·JDK 25·Gradle 9.7.1·PG 17·Redis 7·Testcontainers 2.0.5·ArchUnit 1.5.0·Node 22)로, 쇼핑 전용 절(카트·셀러·SpotBugs·find-sec-bugs·claude-code-action·support)을 지우고 이 저장소가 밟은 사실(on conflict·부분 유일 인덱스·query 별칭·measure up-to-date)을 더했다. `identifier-rules.md`: 노출 번호는 티켓만, 나머지는 내부 id + 404. `external-references.md`: D 번호를 이 저장소 것으로. 닫힘: `StackVersionConsistencyTest`(빠른 레인, 표 ↔ 빌드·wrapper·compose·CI) 초록 | 완료 |
| I1 | 점검 1차 · 세로 제약 | 완료 — 이름 붙은 제약·트리거·인덱스 108. 트리거 20 중 테스트가 치는 것 19 → `seat_grade_event_frozen` 테스트 추가. 한 행 안 check 43 은 `D14` 대로 안 잰다. 도메인 불변식인 유일 셋(`performance_seat_key`·`reservation_seat_key`·`refund_payment_id_key`)에 직접 치는 테스트 추가. 지연 트리거 셋은 전부 커밋 레인에서 돈다. 누락 둘 → `13a`. 판정 근거는 이력 | 완료 |
| I2 | 점검 2차 · 가로 규약 | 완료 — `D1`~`D21` 중 검증 가능한 결정을 코드와 대조했다. **맞은 것**(근거를 남겨 다시 안 재게): `D3` 전이표 = `V19` 트리거, `D6` 구간표 = `V10` 시드 넷, `D11` 카탈로그 = `EventType`(안 내는 것도 적혀 있다), `D12` 숫자(C 2,000·M 100·R 20/초·TTL 10분) = `AdmissionService` 상수, `D20` ETag·304 = `SeatController`, `D21` `payout_delay_days` = `SettlementPolicyQuery`, `D2` 아웃박스 7일 = `OutboxCleaner`. **지금 고침 2**: `D5` 계약표에 `account-not-found` 추가 + `ErrorContractTest` 신설(슬러그·상태 양방향), `D9` 무차별 대입 숫자(10분/10분 → 실물 이메일+IP 5회·15분 키 하나). **입력 구멍도 같이 막았다** — 문서를 글자로 읽는 테스트 셋이 있는데 그 문서가 Gradle 입력도 레인 지문도 아니라 문서만 고친 커밋에서 안 돌았다. **신규 청크 셋**: `13b`·`I2-1`·`P10a`. 판정 근거는 이력 | 완료 |
| 13b | 멱등키 청소 | `D4` 가 「보관 24시간」이라 정했고 `V7` 주석과 `idempotency_key_created_idx` 가 그것을 전제하는데 **지우는 코드가 없다**(점검 2차). 스케줄러 열 중 아무도 안 본다 — 표가 무한히 는다. `OutboxCleaner`(25a)와 같은 모양이다. **축**: `D4` 「멱등키」 + `D2` 수명. **강제 지점**: 테스트(24시간 지난 행은 지우고 경계는 남긴다) + 33 의 스케줄러 락. **건드리는 자리**: 신설 `idempotency/IdempotencyCleaner`. **닫힘**: `IdempotencyCleanerTest` — 완료(`IdempotencyCleaner`, 한 시간마다 `created_at < now() - 24h` 를 지운다. **락을 안 쓴다** — 시각 조건 `DELETE` 는 두 대가 같이 돌아도 결과가 같고 늦은 쪽이 0행이다. 예보는 락을 쓴다였고 `OutboxCleaner` 선례를 따라 뺐다. 테스트 셋: 지난 것만 지운다·경계는 남는다·둘째 회는 0. `D4`·`D2` 에 청소하는 자리를 적었다) | 완료 |
| I2-1 | 열거형과 `check` 대조 | DB 가 값 목록을 닫아 둔 자리 **14** 곳과 Kotlin 열거형 **10** 개가 갈리는 것을 막는 것이 없다(점검 2차). 값을 열거형에만 더하면 삽입이 런타임에 제약으로 죽고, `check` 에만 더하면 열거형 변환이 죽는다 — 둘 다 컴파일은 초록이다. ProjectShop 의 `EnumConstraintTest` 가 하던 일이고 `D18` 은 이 저장소에도 있는 것처럼 적어 뒀다. **축**: `D14` 「열거값을 어디에 두나」 + `D18`. **강제 지점**: 테스트(마이그레이션의 `check (x in (...))` 와 열거형 `code` 집합이 같다). **건드리는 자리**: 신설 `EnumConstraintTest`. **닫힘**: 그 테스트 — 완료(`EnumConstraintTest`. 마이그레이션을 **판 순서로** 읽고 `drop constraint` 를 반영한다 — `V17` 이 `performance_status_check` 를 떨구고 다시 걸어서 그냥 훑으면 낡은 목록이 같이 잡힌다. **테스트가 스스로 둘을 더 찾았다**: `outbox_type_check`·`notification_event_type_check` 는 여러 줄 `check` 라 손으로 센 14 에 안 잡혔다. 거울 11 · 부분집합 1(알림은 `performance.closed` 를 안 받는다) · 열거형을 일부러 안 둔 것 5. 목록을 새로 닫으면 셋 중 어디로 갈지 정해야 통과한다. DB 에만 값을 더해 보니 짚었다) | 완료 |
| P10a | `D18` 을 이 저장소 것으로 | 게이트 표가 ProjectShop 것이다 — 아홉 중 `ArchitectureTest`·`StackVersionConsistencyTest` 둘만 실재하고, 나머지 일곱(`EnumConstraintTest`·`OpenApiSpecTest`·`ActionPermissionTest`·`SellerSchemaTest`·`PublicSurfaceTest`·`TestConventionTest`·`PlanProgressConsistencyTest`)은 없다. 테스트 수도 972+29 로 적혀 있는데 점검 당시 실제 310 이고(이 묶음이 더해 318) frontend 는 아직 없다. **문서의 목적이 「무엇이 막는다고 말할 수 있나」라 표가 틀리면 반대로 일한다.** `47` 이 detekt 행만 더하고 나머지를 안 봤다. **축**: `D18` 자신 + 관례. **강제 지점**: 문서 — 더 아래로 못 내린다. 다만 `I2-1` 이 세우는 게이트는 여기 행이 된다. **건드리는 자리**: `doc/reference/quality-gates.md`(이식 머리말 삭제 포함). **닫힘**: 표의 모든 행이 실재하는 게이트다 — 완료(게이트 20 행으로 다시 썼다. 「부순 날」이 빈 줄이 여덟이고 **그것이 아직 증거가 없다는 뜻**이라고 표 밑에 적었다 — 전에는 ProjectShop 의 증거가 차 있어서 이 저장소가 다 검증된 것처럼 읽혔다. 표를 설명하던 뒤 문단 셋도 실물에 맞췄다. **이식 머리말은 안 지웠다** — 다른 절의 예시가 아직 ProjectShop 것이라, 머리말을 「이 표만 믿어도 된다」로 좁혔다) | 완료 |
| I3 | 점검 3차 · 세로 테스트 | 완료 — `D8` 의 층 표와 규칙 절에서 내려가며 「이건 왜 아직 테스트에 있나」를 물었다(테스트 63 파일 — 빠른 레인 9·`db` 52·`measure` 1). **맞은 것**(근거를 남겨 다시 안 재게): 1인 4매·중복 선점은 `reservation_live_hold_idx` 가 들고 `index_itself_rejects_two_live_holds` 가 인덱스를 직접 친다, 감사 정리가 보존 가드를 끄는 우회는 `AuditImmutabilityTest` 셋이 따로 잰다, 테스트 바탕의 컨테이너 이미지 ↔ `stack.md` ↔ compose 는 `StackVersionConsistencyTest` 가 이미 잰다, `Thread.sleep` 넷은 전부 「없음을 재는 자리」거나 밀리초 해상도라 규약 위반이 아니다. **지금 고침 4**: `.github/workflows/ci.yml` 입력 구멍(아래), `D8` HTTP 층 행(「필요해질 때」인데 `33` 이 열었다), `4a` 구멍 문장(`V20` 이 닫았는데 열려 있다고 적혀 있었다), `Waits` 관례 한 줄. **신규 청크 2**: `I3-1`·`I3-2`. 판정 근거는 이력 | 완료 |
| I3-1 | `StateMachineDocTest` | 문서 전이표와 코드 전이표를 대조하는 것이 **아무 층에도 없다**(점검 3차). `D8` 표·`state-machines.md`(현재형 「대조한다」)·`PLAN` 의 `20` 행(「`13` 이 세운다」) 셋이 있다고 말하는데 코드에 없고 `13` 은 완료다. 지금 대조한 것은 `I2` 가 손으로 한 번 댄 것뿐이라, 표를 한쪽만 고치면 다음 사람이 낡은 쪽을 믿는다. **축**: `D3` + `D8`. **강제 지점**: 테스트(빠른 레인 — `state-machines.md` 의 전이표 = `V19` 트리거가 허용하는 전이). **건드리는 자리**: 신설 `StateMachineDocTest` + 그 문서를 `test` 입력·backend 레인에 더한다. **닫힘**: 문서에서 전이 한 줄을 지우면 빨개진다 — 완료(`StateMachineDocTest`. 문서의 `### 전이표` 둘과 `reservation_status_transition`·`performance_status_transition` 이 허용하는 짝을 양방향으로 맞춘다. **회차 절에 전이표를 새로 세웠다** — 그림과 산문뿐이라 대조할 표가 없었다. 마이그레이션은 판 순서로 읽고 마지막 `create or replace` 만 본다(`V17`·`V19` 가 갈아 끼웠다). 트리거가 생겼는데 표가 없으면 둘째 테스트가 먼저 선다. `state-machines.md` 를 `test` 입력·backend 레인에 더했다 — 안 걸었더니 그 문서만 고친 판에서 `UP-TO-DATE` 로 건너뛰었다) | 완료 |
| I3-2 | 예상 대기를 단위 층으로 | `D8` 층 표가 「순번→예상 대기」를 **단위 층** 예시로 들고 「`21` 부터」라 적었는데, 실물은 `QueueService` 안의 한 줄 식이고 재는 것은 컨테이너 둘(PG·Redis) 위의 `QueueRankTest.eta_comes_from_the_admission_rate` 하나다 — 경계(올림)를 재려고 Redis 에 filler 를 스무 개 넣는다(점검 3차). **축**: `D8` 층. **강제 지점**: 빠른 레인 단위 테스트. **건드리는 자리**: `QueueService`(식을 이름 붙은 함수로) + 신설 `QueueEtaTest`. **닫힘**: `gradlew test` 에서 경계 넷(0·1·20·21)이 컨테이너 없이 돈다 — 완료(`QueueService.etaSecondsFor(rank)` 로 뽑고 `QueueEtaTest` 가 빠른 레인에서 잰다. 예보는 경계 넷에 `0` 을 넣었는데 **순번이 1부터라 0 이 안 들어온다** — 1·20·21·40·41 과 「순번이 늘어도 예상이 안 줄어든다」 하나로 바꿨다. 인자를 보이는 순번(1부터)으로 받으면서 식이 올림 나눗셈 그 자체가 됐다 — 전에는 `ZRANK`(0부터)에 R 을 더해 같은 답을 냈다. `QueueRankTest` 의 통합 경계는 남겼다: R 을 `AdmissionService` 가 정하니 들이는 쪽과 이어졌는지는 컨테이너 위에서만 보인다) | 완료 |
| I4 | 점검 4차 · 세로 앱 검증 | 완료 — 서비스·검증기에서 출처로 올라가며 훑었다(검증 애너테이션 13 · `TicketException` 던지는 자리 33 · 커스텀 제약 셋). **맞은 것**(근거를 남겨 다시 안 재게): `@RequestBody` 여섯 곳이 전부 `@Valid` 다, 요청 `data class` 의 제약이 **전부 DB 거울을 가진다**(displayName 50·title 1~200·email 254 옥텟·등급 코드 정규식·구역 정규식·`price >= 0`), 관문 필터가 토큰의 계정·회차를 본다, 오류 `detail` 과 로그에 이메일·카드가 안 흐른다, 4매 상한이 앱 셈인 것은 `reservation_live_hold_idx` 가 경합을 받쳐서다(`V8` 주석). **지금 고침 1**: 구역 이름 원소 제약이 **걸려만 있고 안 돌았다** — `List<@Pattern …>` 을 Hibernate Validator 가 못 본다(검증기를 직접 돌려 위반 0, 같은 객체의 `title`·`code` 위반은 잡히는 것으로 갈랐다). 잘못된 구역 이름이 앱을 지나 DB check 에 걸려 400 대신 500 이 나갔다 — `@SectionCodes` 로 내리고 `SectionCodesTest` 를 세웠다. **신규 청크 1**: `I4-1`. 판정 근거는 이력 | 완료 |
| I4-1 | 앱 검증과 DB 제약 대조 | 같은 규칙을 **두 벌** 든다 — 길이 셋(displayName 50·title 200·email 254)·정규식 둘(등급 코드·구역)·`price >= 0`. 값 목록은 `I2-1` 이 닫았는데 **길이와 정규식은 아무도 안 잰다**(점검 4차). 갈리면 느슨해진 쪽을 지난 요청이 DB 제약에 걸려 400 대신 500 으로 나간다 — 구역 제약이 실제로 그 모양이었다. **축**: `D14` 강제 지점 + `D5`. **강제 지점**: 테스트(요청 `data class` 의 제약을 리플렉션으로 읽어 마이그레이션의 check 와 맞춘다). **건드리는 자리**: 신설 `AppDbConstraintTest`. **닫힘**: 한쪽 숫자·정규식을 바꾸면 빨개진다 — 완료(`AppDbConstraintTest`. 길이 셋은 애너테이션의 `max` 와 `check` 의 상한을 숫자로 맞추고, 정규식 둘은 글자로 맞춘다(구역은 `seat`·`seat_grade_map` 두 표라 셋을 잰다). `price` 만 모양이 다르다 — 앱은 `@PositiveOrZero` 라 값이 없어서 **있음과 `>= 0` 을 따로 본다**. **예보와 달랐던 것**: 구역 형식은 애너테이션에 없고 검증기 안 상수라 `SectionCodesValidator.FORMAT_REGEX` 로 공개했다. `check (…)` 안쪽은 괄호 짝을 세어 떼낸다 — `length(title) between 1 and 200` 처럼 안에 괄호가 또 있다. 이메일은 앱이 글자·DB 가 옥텟이라 **숫자만** 맞춘다) | 완료 |
| I5 | 점검 5차 · 가로 표준 | 완료 — `external-references.md` 의 인용에서 코드로 내려가며 「이 요건이 어디에 박혔나」를 물었다. **박혀 있던 것**(근거를 남겨 다시 안 재게): RFC 9457 의 `application/problem+json` 은 MVC 경로(`ProblemDetailTest`)와 필터 경로(`ProblemWriter`) 둘 다, RFC 9110 의 401 챌린지는 진입점과 핸들러 두 자리가 같은 값, RFC 9562 UUIDv4 는 `IdempotencyKeys` 가 변종 자리(`[89ab]`)까지 본다, RFC 5321 254 옥텟은 DB 가 `octet_length` 로 재고 `I4-1` 이 앱과 맞춘다, NIST 15자·블록리스트는 `Password`·`PasswordBlocklist`, 조합 강제는 없다(SHALL NOT), W3C `traceparent` 는 5 가 `w3c` 로 맞췄다, Zalando snake_case 는 핸들러가 필드 이름을 바꾼다. **지금 고침 1**: ISO/IEC 7812 의 12~19 자리가 **입구에 없었다** — `@Pattern` 이 가운데를 구분자로 채우게 둬서 `1 - - - - - - - - - -2`(두 자리)가 통과했고(검증기로 확인), 게이트웨이의 `require` 가 `IllegalArgumentException` 을 던져 400 이 아니라 500 으로 나갔다. `@CardNumber`+`CardNumbers` 로 셈을 한 자리에 두고 `CardNumberTest` 를 세웠다. **문서 고침 1**: CloudEvents 행이 「25 가 정한다」로 미정인데 `event-catalog.md` 가 안 쓰기로 닫았다. **신규 청크 1**: `I5-1`. 판정 근거는 이력 | 완료 |
| I5-1 | 바깥 근거 다시 열기 | `external-references.md` 의 링크를 **2026-08 에 ProjectShop 에서 연 것이 마지막**이고 `P11` 도 다시 안 열었다. 회사 문서(Zalando·Stripe·Spring·OWASP)는 갱신되고 Testcontainers 이슈 둘은 닫혔을 수 있다 — 낡은 근거는 **틀린 줄 모르고 인용된다**. 축이 아니라 「바깥 근거」 단위다(점검 5차). **축**: 표준·관례. **강제 지점**: 없다 — 문서의 확인 이력이 전부다. **건드리는 자리**: `doc/reference/external-references.md`. **닫힘**: 링크마다 열린다·닫혔다·옮겼다 중 하나가 적히고 확인일이 선다 — **진행중**(2026-09-19: **열넷을 열었다** — OWASP 링크가 308 로 옮겨져 고쳤고, W3C Trace Context 는 Recommendation 이라 「Level 2 초안」 문구를 고쳤고, Testcontainers 이슈 둘은 닫혔다. NIST·Zalando 는 적힌 대로다. PostgreSQL §13.2.1·Stripe 멱등·CloudEvents·SQL Style Guide·SRE Book·Outbox·Test Pyramid·Money 는 적힌 그대로다. **남은 열넷**: RFC 다섯·Spring 셋·Stripe Connect 넷·법제처·DDD PDF) | 진행중 |
| I6 | 점검 6차 · 계획 자체 | 완료 — 분할표 105행과 기준 문서 21행을 실물과 맞췄다. **낡은 것 셋**: `D14`·`D15` 가 `이식됨 → P1`·`P2` 인데 **그 행이 분할표에 없다** — `6` 이 흡수했다(`PLAN` 의 `6` 행·이력 둘 다 그렇게 적혀 있다). `D18` 은 `P10a` 가 닫았는데 `이식됨 → P10` 이었다. `PROGRESS` 의 기준 문서 행이 `20개` 로 세고 있었다(실제 21). **겹친 것 하나**: `P10` 이 아직 「이식」인데 `P10a` 가 표를 다시 썼다 — 남은 것이 머리말과 CodeQL 행뿐이라 설명을 거기로 좁혔다. **쪼갠 것 하나**: `46`(codeql·claude-review·e2e)의 선행이 `2·39` 라 **CodeQL·claude-review 가 화면을 기다리고 있었다.** 사용자가 화면을 미뤄서 무기한이다 — `46a`(선행 `2`)·`46b`(선행 `42`)로 갈랐고 `P10` 의 선행도 `46a` 로 옮겼다. 판정 근거는 이력 | 완료 |
| 48 | Railway 배포(선택) | 사용자가 켜면. **축**: 관례. **강제 지점**: 없다. **건드리는 자리**: `Dockerfile`·env. **닫힘**: 공개 URL 의 `/api/health` 200 | 38 |

## 안 만드는 입구

**부르는 곳이 0 이라 안 만든다**(대전제 3). 필요해지면 그때 청크를 세운다 — 여기 적어 두는 이유는 다음 사람이 빠뜨린 줄 알고 채우지 않게 하려는 것이다.

- **공연장·홀·좌석 등록 API** — 좌석 배치는 한 번 정하면 안 바뀌고(`seat` 는 수정·삭제 불가, `V4`) 부르는 화면이 없다. 지금은 `DemoSeeder` 가 SQL 로 넣는다(12)
- **기획사·계정 생성(관리자)** — 기획사는 계약으로 생기는 것이라 화면이 없다. 관객 계정은 가입(3)이 만든다. 지금은 시드·`psql`
- **정산 조회·전이 API** — 부르는 화면이 `45` 라 그 청크가 같이 연다(`D21`)

## 이 계획을 고칠 때

- 완료 행은 안 고친다. 그때 그렇게 쳤다는 기록이다
- 새 행은 넷을 다 적는다. 빠지면 `doc-lint.sh` 가 빨갛다
- 청크를 흡수·분할하면 이력에 밝힌다
