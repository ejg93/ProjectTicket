# 진행 로그

## 현재 상태

**대상**: 공연 예매 시스템 (Kotlin + Spring Boot 4 + Next.js, 로컬 전용). **가지**: `main` (첫 커밋 전)

| 무엇 | 상태 | 다음 손 |
|---|---|---|
| 진행중 청크 | 없음 | |
| 다음 첫 손 | **`1` backend 골격** — Kotlin + Boot 4 + Flyway + `/api/health`. 그다음 `2`(CI·GitHub 저장소) → `3`(인증 포팅) | `PLAN.md` 의 `1` 행 |
| GitHub 저장소 | **아직 없다.** `2` 에서 만든다 — 이름·public 여부는 사용자 확인 | `2` |
| 기준 문서 | D1·D2 완료(초안). 나머지 15개는 이식 머리말 붙은 채. `P` 줄이 닫는다 | 각 `P` 행의 선행 |
| 이식 원본 | `C:\workspace\ProjectShop` 읽기 전용. 가져온 것·안 가져온 것은 ADR 0002 | |

## 이력

| 날짜 | 청크 | 결과 | 커밋 |
|---|---|---|---|
| 2026-09-14 | 0. 저장소 뼈대 | 완료 — `CLAUDE.md`·`PLAN.md`·`PROGRESS.md`·`README.md`·`docker-compose.yml`·`.claude/settings.json`·스킬 4·`scripts/` 3·`.github/` 신설. ADR 0001(스택)·0002(이식 범위). `doc/reference/` 15개를 머리말 달아 원문 이식, `domain-model.md`·`glossary.md` 신설. **왜**: 사용자가 Kotlin+Spring 을 골랐고(국내 공고 60~70% Java+Spring, 주요 IT 가 Kotlin), Kafka 는 소비자 둘째에서, k8s 는 로컬 kind, AWS 는 안 한다. ProjectShop 은 docs 313 대 feat 142 라 문서 비중을 낮추기로 했다(대전제 5). **검증**: `bash scripts/doc-lint.sh` 통과 | cb20edf |

## 기록 규칙

- 완료했으면 「현재 상태」 표의 걸린 행을 고치고 「이력」에 한 줄 더한다. **새 줄은 맨 아래다** — 날짜순
- 「현재 상태」는 표다. 서사를 안 둔다. 25줄을 넘으면 `doc-lint.sh` 가 빨갛다
- 중간에 멈췄으면 `진행중 청크` 행에 번호와 남은 작업을 **파일명 단위**로 적는다
- 이력 한 줄은 넷을 이 차례로: **무엇을** 만들었나 → **왜** 그렇게 정했나(편 축·갈림길·사용자 선택) → **무엇이** 예보와 달랐나 → **검증** 명령. 빈 칸은 안 쓴다
- 굵게는 결정에만. 파일 이름·명령·수치는 백틱
- `PLAN.md` 완료 행은 결과(서너 줄), 이력은 서사. 같은 말을 두 벌 안 쓴다
