---
name: verify
description: 청크를 닫기 전에 무엇을 돌리나. `/verify` 또는 「검증」. `bash scripts/verify.sh` 가 레인을 고르고 도장을 찍는다. 실제로 돌려본 것만 됐다고 한다.
---

# 검증

**무엇을 건드렸는지가 무엇을 돌릴지 정한다.** 청크를 닫기 전에 걸리는 줄을 **전부** 돌린다.

**먼저 `bash scripts/verify.sh`.** `origin/main` 대비 레인 지문(코드·빌드 파일만 — `scripts/verify-fingerprint.sh`)이 다르면 그 레인을 돌리고 초록이면 `.git/verify-stamp` 에 찍는다. 레인은 셋 — `backend`·`frontend`·`tools`(검증 도구 자체).

| 단계 | 명령 | 무엇이 도나 | 누가 요구하나 |
|---|---|---|---|
| **빠른 도장** | `bash scripts/verify.sh` | backend `gradlew test`(컨테이너 없음) · frontend `tsc`·lint·test | **Stop hook** — 청크를 닫을 때 |
| **full 도장** | `bash scripts/verify.sh --full` | backend `gradlew build`(Testcontainers 레인 포함) · frontend `next build`·lint·test | **push hook** — 마무리 앞 한 번 |

full 은 Docker 를 먼저 본다. 안 떠 있으면 한 줄로 끝낸다.

## 실제로 돌려본 것만 됐다고 한다

**돌리지 못했으면 못 돌렸다고 밝힌다.** 안 돌려보고 「동작한다」「빌드 통과」라고 쓰지 않는다.

**backend 명령은 앞에 `JAVA_HOME="C:/Program Files/Java/jdk-25"` 를 붙인다.** 안 붙이면 훅이 막는다.

**시점이 셋이다** — 청크(커밋 앞) · 번들 끝(`/wrapup`) · push 뒤. `CLAUDE.md` 「검증」이 층을 정하고 이 표가 명령을 든다.

| 언제 | 시점 | 명령 | 통과 기준 |
|---|---|---|---|
| backend 를 건드렸으면 | 청크 | `./gradlew test`(= `verify.sh`) | 실패 0 |
| backend 를 건드렸으면 | 번들 끝 | `./gradlew build`(= `verify.sh --full`) | `BUILD SUCCESSFUL`. `test`·`integrationTest` 두 레인 |
| **새 `V*` 를 더했으면** | 청크 — `verify.sh` 가 지문에서 보고 **스스로** 돈다 | `./gradlew test integrationTest` | 실패 0. 빠른 레인만 돌리면 열거형·길이 대조가 번들 끝에서 처음 빨개진다. **Docker 가 없으면** `test` 만 돌고 도장이 `fast-nodb` — 번들 끝 `--full` 이 잡는다 |
| 스키마·서비스만 볼 때 | 아무 때 | `./gradlew integrationTest` | 실패 0. 컨테이너 레인 |
| **좌석·예매를 건드렸으면** | 번들 끝 | `./gradlew integrationTest --tests '*Concurrency*'` | 실패 0. 이 레인이 빠지면 동시성 결함이 push 까지 숨는다 |
| **수치를 남길 때**(락 비교·부하) | 그 청크 | `./gradlew measure` | `build` 밖이다(`D8`). 표를 `doc/notes/` 에 기계 사양과 같이 적고 ADR 이 읽는다. 늘 다시 돈다 — 건너뛰면 지난 숫자를 이번 것으로 읽는다 |
| **새 `V*` 가 있었으면** | 번들 끝 | 빈 DB 로 `POSTGRES_DB=ticket_check ./gradlew bootRun --args='--spring.profiles.active=local'` 후 `curl localhost:8080/api/health` | `applied_migrations` 가 파일 수와 같다. 테스트만으로는 기동 경로를 안 지난다 |
| 화면을 건드렸으면 | 청크 | `npx tsc --noEmit && npm run lint && npm test`(= `verify.sh`) | 초록. lint 는 `--cache` 라 둘째부터 바뀐 파일만 |
| 화면을 건드렸으면 | 번들 끝 | `cd frontend && npm run build && npm run lint && npm test`(= `verify.sh --full`) | 초록 |
| 예매·대기열 화면을 건드렸으면 | 번들 끝 — **`46b` 뒤에만.** `verify.sh --full` 이 Playwright 와 `e2e` 스크립트가 있을 때만 돈다 | 백엔드 `local` 로 띄운 뒤 `npm run e2e` | 통과. Playwright 는 있는데 백엔드가 안 떠 있으면 빨갛다 |
| 컨테이너 설정을 건드렸으면 | 청크 | `docker compose config --quiet && docker compose up -d` | `ticket-db`·`ticket-redis` healthy |
| k8s 를 건드렸으면 | 청크 | `bash scripts/k8s-smoke.sh`(청크 36 부터) | `/api/health` 200 |
| **문서를 고쳤으면** | — | 안 돌려도 된다 — 훅이 편집 직후에 `doc-lint.sh <그 파일>` 을 돌린다(범위 모드, 1초 안). 전체는 `bash scripts/doc-lint.sh`(16초) — 마무리·CI | 통과하면 아무 말 없음 |
| **검증 도구를 고쳤으면**(`scripts/`·`settings.json`) | 청크 | `tools` 레인(= `verify.sh`) — `bash -n` 전부 · `settings.json` 파싱 · `doc-lint` 전체 | 초록. 훅 본문은 `scripts/hooks/*.sh` 라 stdin 에 JSON 을 넣어 exit 코드를 직접 볼 수 있다 |
| 푸시했으면 | push 뒤 | 아래 「CI」 | 초록. 빨가면 다음 청크보다 먼저 친다 |

청크 verify 가 빨가면 고치기 둘까지. 그래도면 `wip/<청크>` 가지에 커밋하고 번들 가지로 돌아와 다음 행 — 번들이 한 행에 안 잡히게. `work/*` 에는 도장 없는 커밋이 못 올라간다(commit hook).

**같은 지문은 두 번 안 돈다.** `verify.sh` 가 도장을 읽어 그 레인의 지문이 같고 단계가 같거나 높으면 건너뛴다. backend 청크 여덟이 frontend 레인(109초)을 매번 돌리면 번들에 9분이 붙어서다.

**`(= verify.sh)` 라 적힌 줄의 명령은 스크립트와 같아야 한다** — 출력 다듬기(`>/dev/null`·`2>&1 | tail`)만 빼고. 마무리 대조 첫 줄이 이 표를 본다.

**새 구역이 생기면 그 명령을 이 표에 더한다.**

## CI

커밋마다 `.github/workflows/ci.yml` 이 같은 명령을 돈다. 푸시해야 돌고 가지를 안 가린다 — PR 이 없어도 청크마다 리눅스 검증을 받는다.

| 무엇 | 명령 |
|---|---|
| 최근 결과 | `gh run list --limit 3` |
| 끝날 때까지 | `gh run watch <id> --exit-status` |
| **실패한 부분만** | `gh run view <id> --log-failed` |

`gh` 가 `PATH` 에 없으면 `"/c/Program Files/GitHub CLI/gh.exe"`.

CI 는 로컬 Windows 가 못 잡는 것을 잡는다 — 실행 비트, 대소문자 경로, `npm ci` 와 잠금 파일 불일치.
