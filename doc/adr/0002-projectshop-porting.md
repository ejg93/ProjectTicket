# ADR 0002 — ProjectShop 에서 무엇을 가져오나

2026-09-14. 사용자가 추천안을 그대로 골랐다.

## 원본 관찰

커밋 660개 중 docs 313·feat 142. 문서·프로세스가 코드의 2배다. `CLAUDE.md` 25KB, `PLAN.md` 442KB, `PROGRESS.md` 655KB.
토큰 소모 목적에는 맞지만 예매 시스템 공부에는 프로세스가 배움을 가린다.

## 결정

| # | 항목 | 처분 | 어떻게 |
|---|---|---|---|
| 1 | 청크 워크플로(CLAUDE.md·PLAN·PROGRESS·훅·스킬) | **가져오되 슬림화** | `CLAUDE.md` 10KB 이하. 훅은 main 금지·검증 도장·문서 린트·미커밋 검사·JAVA_HOME. 스킬은 warmup·verify·wrapup·inspection. `explain` 은 안 가져온다 |
| 2 | 기준 문서 | **골라서** | 가져옴: coding·naming·api·testing·security·observability·concurrency·state-machines·time·frontend·screen·quality-gates·identifier·external-references·stack. 버림: commerce-compliance·permission-*·money-*·batch-catalog·business-model·domain-model·notification·data-lifecycle·glossary·document-map(새로 쓴다) |
| 3 | ADR 관례 | 가져옴 | |
| 4 | CI(ci·codeql·claude-review·e2e·dependabot) | 가져옴 | `ci.yml`·dependabot 은 뼈대에서. codeql·claude-review·e2e 는 청크로 |
| 5 | 백엔드 auth·account·audit·consent·error·health·observability | **Kotlin 포팅** | 도메인 무관 모듈 |
| 6 | 백엔드 payment(mock)·notification | 가져와서 개조 | 모의 PG·환불 상태기계 재사용 |
| 7 | 백엔드 product·cart·order·seller·settlement | 안 가져옴 | 공연·좌석·예매·정산은 새로 |
| 8 | 프론트 뼈대(Next.js·`api.ts`·login/signup/me) | 가져옴 | 좌석 배치도는 새로 |
| 9 | docker-compose(Postgres+Redis) | 가져와 확장 | Kafka·Prometheus·Grafana 는 그 청크에서 |
| 10 | verify·doc-lint 스크립트 | 가져옴 | `req-coverage.sh` 는 법 요건표가 없어 안 가져온다 |

## 이식 방식

가져온 기준 문서는 **머리말을 달고 원문 그대로** 들어왔다. 예시가 쇼핑 도메인이라 그대로 믿으면 틀린다.
이식 청크(`P` 줄)가 하나씩 티켓 도메인과 Kotlin 에 맞추고 머리말을 지운다. **원칙은 믿고 예시는 안 믿는다** 가 그때까지의 규칙이다.
