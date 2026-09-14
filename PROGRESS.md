# 진행 로그

## 현재 상태

**대상**: 공연 예매 시스템 (Kotlin + Spring Boot 4 + Next.js, 로컬 전용). **가지**: `work/2026-09-14-b`

| 무엇 | 상태 | 다음 손 |
|---|---|---|
| 진행중 청크 | 없음 | |
| 다음 첫 손 | **`4` 감사·동의 포팅** 또는 **`5` 관측 포팅** — 둘 다 선행 `3` 완료, 서로 독립 | `PLAN.md` 의 `4`·`5` 행 |
| PR | `#2` 머지됨(청크 2). 청크 3 은 `work/2026-09-14-b` 에 있고 마무리 2차가 연다 | `/wrapup` |
| GitHub 저장소 | `https://github.com/ejg93/ProjectTicket`. `main` 가지 보호 걸림(네 잡 필수). 그 뒤로는 `work/<날짜>` + PR | |
| 로컬 포트 | ProjectShop 컨테이너가 5432·6379 를 쥐고 있어 `.env` 는 5433·6380 | `README.md` |
| 기준 문서 | D1·D2 완료(초안). 나머지 15개는 이식 머리말 붙은 채. `P` 줄이 닫는다 | 각 `P` 행의 선행 |
| 이식 원본 | `C:\workspace\ProjectShop` 읽기 전용. 가져온 것·안 가져온 것은 ADR 0002 | |

## 이력

| 날짜 | 청크 | 결과 | 커밋 |
|---|---|---|---|
| 2026-09-14 | 0. 저장소 뼈대 | 완료 — `CLAUDE.md`·`PLAN.md`·`PROGRESS.md`·`README.md`·`docker-compose.yml`·`.claude/settings.json`·스킬 4·`scripts/` 3·`.github/` 신설. ADR 0001(스택)·0002(이식 범위). `doc/reference/` 15개를 머리말 달아 원문 이식, `domain-model.md`·`glossary.md` 신설. **왜**: 사용자가 Kotlin+Spring 을 골랐고(국내 공고 60~70% Java+Spring, 주요 IT 가 Kotlin), Kafka 는 소비자 둘째에서, k8s 는 로컬 kind, AWS 는 안 한다. ProjectShop 은 docs 313 대 feat 142 라 문서 비중을 낮추기로 했다(대전제 5). **검증**: `bash scripts/doc-lint.sh` 통과 | cb20edf |
| 2026-09-14 | 1. backend 골격 | 완료 — `backend/build.gradle.kts`·`settings.gradle.kts`·Gradle 래퍼(ProjectShop 것), `TicketApplication.kt`·`health/HealthController.kt`, `application.yml`·`application-local.yml`, `V1__baseline.sql`, `PostgresTestBase.kt`·`HealthControllerTest.kt`, `backend/README.md`·`backend/CLAUDE.md` 신설. **왜**: 축은 관례 — Kotlin 버전은 Boot 4.1.1 BOM 이 관리하는 2.3.21 에 맞췄고(플러그인은 BOM 밖이라 직접 적는다), `-Xjsr305=strict` 로 Spring 의 널 어노테이션을 Kotlin 타입에 반영한다. 테스트 레인은 ProjectShop 과 같이 태그로 가른다 — 컨테이너 유무가 `PostgresTestBase` 상속에 따라오므로 빠뜨릴 자리가 없다. ProjectShop 의 fork 별 DB 분리·Redis 컨테이너·bcrypt 비용 조정은 안 가져왔다 — 쓰는 청크(13·21·3)에서 들인다. **달랐던 것**: `@ServiceConnection` 을 썼다 — ProjectShop 은 fork 별 DB 때문에 못 썼지만 여기는 아직 fork 가 하나다. 로컬 5432·6379 를 ProjectShop 컨테이너가 쥐고 있어 `.env` 를 5433·6380 으로(정보 → `README.md`). **검증**: `JAVA_HOME=jdk-25 ./gradlew build` `BUILD SUCCESSFUL` — `test` 0개(빠른 레인 비어 있음), `integrationTest` 1개 통과. `bootRun` 뒤 `curl /api/health` → `{"app":"ticket-backend","database":"ticket","applied_migrations":1,…}`. `verify.sh` 는 `origin/main` 이 없어 못 돌렸다 | 1f8bcb6 |
| 2026-09-14 | 2. CI 첫 초록 | 완료 — `backend/gradlew` 모드 100755, `main` 가지 보호(API). **왜**: 축은 관례(GitHub Actions). 첫 push 의 CI 가 둘 빨갰다 — backend 는 `./gradlew: Permission denied`(126, Windows 엔 실행 비트가 없어 로컬에선 안 난다), secrets 는 gitleaks 가 루트 커밋의 부모(`cb20edf^`)를 찾다 죽은 것으로 **첫 push 에만 나는 것이라 안 고쳤다** — 다음 push 에서 저절로 초록이 됐다. 가지 보호는 관리자를 안 막는다 — CI 설정 자체가 깨졌을 때 잠기지 않게. **달랐던 것**: push 훅이 명령 문자열을 실행 전에 검사해서 `git checkout -b … && git push` 를 한 명령에 넣으면 아직 `main` 이라고 막는다 — 가지 따기와 push 를 나눠 부른다(정보). **검증**: run `34819188373` 네 잡 초록. `verify.sh` 는 지문(코드 블롭)이 `origin/main` 과 같아 돌릴 것이 없다 | 27a4d2c |
| 2026-09-14 | 3. 계정·인증 포팅 | 완료 — `V2__account.sql`, `error/` 5개, `auth/` 10개, `account/MeController.kt`, `ArchitectureTest.kt`·`LoginFlowTest.kt`·`ProblemDetailTest.kt` 신설. `PostgresTestBase` 에 컨텍스트 소거·bcrypt 4, `application.yml` 에 세션 쿠키, `build.gradle.kts` 에 security·archunit. **왜**: 축은 표준(RFC 9457·RFC 9110 §15.5.2·RFC 5321·NIST 800-63B) + `D9`. **권한 다섯 테이블 대신 `account.role` 컬럼 하나** — 역할이 셋이고 행 단위 스코프가 없어서(ADR 0002) 컬럼 check 가 가장 낮은 강제 지점이다. 기획사 소속은 `organizer_member`(청크 7)가 든다. Kotlin 으로 옮기며 고친 것: enum `of` 를 `entries.firstOrNull`, 필터의 `principal` 을 `is` 스마트캐스트, `TicketUser` 는 `data class` 가 아니다(해시를 지워야 해서). 로그인 실패 카운터는 Redis 가 필요해 `3a` 로 세우고 `21` 뒤에 뒀다. 동의·감사는 `4` 가, `trace_id` 는 `5` 가 이 자리에 붙인다(흡수). **달랐던 것**: Kotlin MockMvc DSL 에서 지역 변수 `session` 이 DSL 속성을 가려 `val cannot be reassigned` — 이름을 바꿨다(정보). 401 본문의 `Content-Type` 에 `charset` 이 붙어 `contentTypeCompatibleWith` 로 봤다. **검증**: `gradlew build` 초록 — 빠른 레인 3(ArchitectureTest), 느린 레인 11. `verify.sh --full` 도장 | 502ca28 |
| 2026-09-14 | 마무리 2차 | 완료 — 대상 청크 3. `doc-lint.sh` 통과, 대조 다섯에서 둘: `auth/` 파일 수 오기(11→10, 분할표·이력 고침), `ProblemDetailTest` 의 테스트 이름이 단언(401)과 달라 이름·주석을 고침. **독립 리뷰: 지적 18, 처분 — 지금 고침 16 · 새 청크 1(`3b` 블록리스트) · 오탐 1**. 고친 것: 401 셋(로그인 실패 핸들러·생존 필터·동시 세션 필터)에 `WWW-Authenticate` 와 problem 본문이 없었다 → 핸들러는 `ResponseEntity` 로, 필터 둘은 `ProblemEntryPoint.commence` 를 부른다. 403 에 `accessDeniedHandler` 가 없어 CSRF 실패가 기본 본문으로 나갔다 → `ProblemAccessDeniedHandler` 신설(`FORBIDDEN` 이 살아났다). 정지 계정을 `isEnabled=false` 로 걸면 bcrypt 전에 거절돼 **응답 시간으로 존재가 샌다** → `isEnabled` 는 항상 true, 정지 판정은 인증 뒤 컨트롤러에서 같은 문구로. `changeSessionId()` 가 생성 시각을 안 바꿔 절대 만료 12h 가 익명 세션 시각부터 셌다 → 로그인 앞에서 세션을 버리고 새로 만든다. 세션 고정 설정이 두 곳(하나는 죽은 설정) → 전략 빈 하나로. 기본 `LogoutFilter` 가 살아 `POST /logout` 이 302 로 답하던 둘째 경로 → 끔, 우리 로그아웃이 `CsrfLogoutHandler` 로 쿠키까지 지움, 로그인 뒤 CSRF 토큰 회전(`CsrfAuthenticationStrategy`). 합성 제약의 `message` 가 `@ReportAsSingleViolation` 없이 죽은 글 → 붙임. 이메일 254 를 `length`(글자)로 재던 것 → `octet_length`(RFC 5321 단위). `data class` 요청의 `toString` 이 평문 비밀번호를 찍음 → 재정의. 생존 필터 SQL 의 `'active'` 리터럴 → `AccountStatus` 로. 프레임워크 오류(406·413·503)의 본문 `status` 가 HTTP 상태와 달랐다(RFC 9457 §3.1) → 실제 상태로. `URI.create(requestURI)` 가 던지면 마지막 그물이 무너짐 → `runCatching`. 메서드 보안의 `AccessDeniedException` 을 500 으로 삼키던 것 → 핸들러 추가. ArchUnit 주석이 규칙보다 넓게 말함 → 주석 좁히고 `HttpStatus` 규칙(error·Controller 밖 금지) 추가. CSRF 진짜 경로가 테스트에 없음 → `CsrfCookieTest` 2개. 레지스트리 만료를 부르는 곳이 없는데 주석·테스트가 있는 것처럼 말함 → 「자리만 있다」로 고침. **오탐**: NIST 15자 SHALL 인용은 맞다(리뷰어가 「모름」). **검증**: `gradlew build` 초록 — 빠른 4, 느린 14. `verify.sh --full` 도장 | |

## 기록 규칙

- 완료했으면 「현재 상태」 표의 걸린 행을 고치고 「이력」에 한 줄 더한다. **새 줄은 맨 아래다** — 날짜순
- 「현재 상태」는 표다. 서사를 안 둔다. 25줄을 넘으면 `doc-lint.sh` 가 빨갛다
- 중간에 멈췄으면 `진행중 청크` 행에 번호와 남은 작업을 **파일명 단위**로 적는다
- 이력 한 줄은 넷을 이 차례로: **무엇을** 만들었나 → **왜** 그렇게 정했나(편 축·갈림길·사용자 선택) → **무엇이** 예보와 달랐나 → **검증** 명령. 빈 칸은 안 쓴다
- 굵게는 결정에만. 파일 이름·명령·수치는 백틱
- `PLAN.md` 완료 행은 결과(서너 줄), 이력은 서사. 같은 말을 두 벌 안 쓴다
