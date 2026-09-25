#!/usr/bin/env bash
# 트리 하나(HEAD·origin/main·임시 트리)의 레인별 지문을 낸다(`P2`). 출력: `backend <sha>` / `frontend <sha>`.
#
# 서브트리 해시를 쓰면 `backend/CLAUDE.md` 같은 문서도 코드로 센다 — 문서만 고쳐도 도장이 바뀌어 레인이 헛돈다.
# 그래서 빌드·테스트 결과를 바꾸는 경로만 고른다. 경로를 더할 때 여기 한 곳만 고친다.
set -uo pipefail
cd "$(dirname "$0")/.."
tree=${1:-HEAD}
lane() {
  local name=$1; shift
  for p in "$@"; do printf '%s %s\n' "$p" "$(git rev-parse -q --verify "$tree:$p" 2>/dev/null || echo -)"; done \
    | git hash-object --stdin | sed "s/^/$name /"
}
# `backend/config` 는 detekt 설정이다(`backend/config/detekt/detekt.yml`). **빌드 결과를 바꾼다** —
# 문턱을 올리면 진짜 검출이 숨는데, 여기 없으면 도장이 안 바뀌어 Stop hook 이 안 막는다.
#
# **다만 빠른 레인은 detekt 를 안 돈다**(`check` 에 달려 있고 `gradlew test` 에는 없다). 그래서 이 경로가
# 실제로 막는 것은 **push 앞 `--full`** 이고, 그 전까지는 「도장을 다시 받아야 한다」까지다.
#
# `Dockerfile`·compose·nginx conf 는 `ComposeContractTest`(35)가 글자로 읽는다. 여기 없으면
# 그 셋만 고친 커밋에서 도장이 안 바뀌고, 35 가 찾은 결함 둘을 막는 검사가 안 돈다.
# `doc/reference/` 다섯과 `.github/workflows/ci.yml` 도 같은 이유다 — `ErrorContractTest`·`MetricNamesTest`·`EventCatalogTest`·`StackVersionConsistencyTest`·`StateMachineDocTest` 가 글자로 읽는다(점검 2차·3차).
lane backend  backend/src backend/config backend/build.gradle.kts backend/settings.gradle.kts backend/gradle backend/gradlew backend/gradle.properties backend/Dockerfile docker-compose.yml docker/nginx doc/reference/api-guidelines.md doc/reference/observability-rules.md doc/reference/stack.md doc/reference/event-catalog.md .github/workflows/ci.yml .github/workflows/codeql.yml .github/workflows/claude-review.yml doc/reference/quality-gates.md doc/reference/state-machines.md
lane frontend frontend/src frontend/package.json frontend/package-lock.json frontend/tsconfig.json \
              frontend/next.config.ts frontend/eslint.config.mjs frontend/vitest.config.mts frontend/vitest.setup.ts
# 검증 도구 자체(`B0-2`). 이 레인이 없으면 verify.sh·훅을 고쳐도 도장이 안 바뀌어 아무 검사도 안 돈다.
lane tools    scripts .claude/settings.json
