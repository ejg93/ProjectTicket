# 진행 로그

## 현재 상태

**대상**: 공연 예매 시스템 (Kotlin + Spring Boot 4 + Next.js, 로컬 전용). **가지**: `main` (첫 커밋 전)

| 무엇 | 상태 | 다음 손 |
|---|---|---|
| 진행중 청크 | 없음 | |
| 다음 첫 손 | **`2` CI 첫 초록** — `origin/main` 이 서면 `work/<날짜>` 가지에서 친다. 그다음 `3`(인증 포팅)·`5`(관측 포팅) | `PLAN.md` 의 `2` 행 |
| GitHub 저장소 | `https://github.com/ejg93/ProjectTicket` (사용자 생성, 2026-09-14). **첫 push 는 사용자가 한다** — 훅이 main 직행을 막아서 `main` 부트스트랩만 손으로. 그 뒤로는 `work/<날짜>` + PR | 사용자 |
| 로컬 포트 | ProjectShop 컨테이너가 5432·6379 를 쥐고 있어 `.env` 는 5433·6380 | `README.md` |
| 기준 문서 | D1·D2 완료(초안). 나머지 15개는 이식 머리말 붙은 채. `P` 줄이 닫는다 | 각 `P` 행의 선행 |
| 이식 원본 | `C:\workspace\ProjectShop` 읽기 전용. 가져온 것·안 가져온 것은 ADR 0002 | |

## 이력

| 날짜 | 청크 | 결과 | 커밋 |
|---|---|---|---|
| 2026-09-14 | 0. 저장소 뼈대 | 완료 — `CLAUDE.md`·`PLAN.md`·`PROGRESS.md`·`README.md`·`docker-compose.yml`·`.claude/settings.json`·스킬 4·`scripts/` 3·`.github/` 신설. ADR 0001(스택)·0002(이식 범위). `doc/reference/` 15개를 머리말 달아 원문 이식, `domain-model.md`·`glossary.md` 신설. **왜**: 사용자가 Kotlin+Spring 을 골랐고(국내 공고 60~70% Java+Spring, 주요 IT 가 Kotlin), Kafka 는 소비자 둘째에서, k8s 는 로컬 kind, AWS 는 안 한다. ProjectShop 은 docs 313 대 feat 142 라 문서 비중을 낮추기로 했다(대전제 5). **검증**: `bash scripts/doc-lint.sh` 통과 | cb20edf |
| 2026-09-14 | 1. backend 골격 | 완료 — `backend/build.gradle.kts`·`settings.gradle.kts`·Gradle 래퍼(ProjectShop 것), `TicketApplication.kt`·`health/HealthController.kt`, `application.yml`·`application-local.yml`, `V1__baseline.sql`, `PostgresTestBase.kt`·`HealthControllerTest.kt`, `backend/README.md`·`backend/CLAUDE.md` 신설. **왜**: 축은 관례 — Kotlin 버전은 Boot 4.1.1 BOM 이 관리하는 2.3.21 에 맞췄고(플러그인은 BOM 밖이라 직접 적는다), `-Xjsr305=strict` 로 Spring 의 널 어노테이션을 Kotlin 타입에 반영한다. 테스트 레인은 ProjectShop 과 같이 태그로 가른다 — 컨테이너 유무가 `PostgresTestBase` 상속에 따라오므로 빠뜨릴 자리가 없다. ProjectShop 의 fork 별 DB 분리·Redis 컨테이너·bcrypt 비용 조정은 안 가져왔다 — 쓰는 청크(13·21·3)에서 들인다. **달랐던 것**: `@ServiceConnection` 을 썼다 — ProjectShop 은 fork 별 DB 때문에 못 썼지만 여기는 아직 fork 가 하나다. 로컬 5432·6379 를 ProjectShop 컨테이너가 쥐고 있어 `.env` 를 5433·6380 으로(정보 → `README.md`). **검증**: `JAVA_HOME=jdk-25 ./gradlew build` `BUILD SUCCESSFUL` — `test` 0개(빠른 레인 비어 있음), `integrationTest` 1개 통과. `bootRun` 뒤 `curl /api/health` → `{"app":"ticket-backend","database":"ticket","applied_migrations":1,…}`. `verify.sh` 는 `origin/main` 이 없어 못 돌렸다 | |

## 기록 규칙

- 완료했으면 「현재 상태」 표의 걸린 행을 고치고 「이력」에 한 줄 더한다. **새 줄은 맨 아래다** — 날짜순
- 「현재 상태」는 표다. 서사를 안 둔다. 25줄을 넘으면 `doc-lint.sh` 가 빨갛다
- 중간에 멈췄으면 `진행중 청크` 행에 번호와 남은 작업을 **파일명 단위**로 적는다
- 이력 한 줄은 넷을 이 차례로: **무엇을** 만들었나 → **왜** 그렇게 정했나(편 축·갈림길·사용자 선택) → **무엇이** 예보와 달랐나 → **검증** 명령. 빈 칸은 안 쓴다
- 굵게는 결정에만. 파일 이름·명령·수치는 백틱
- `PLAN.md` 완료 행은 결과(서너 줄), 이력은 서사. 같은 말을 두 벌 안 쓴다
