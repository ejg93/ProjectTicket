#!/usr/bin/env bash
# 트리 하나(HEAD·origin/main·임시 트리)의 레인별 지문을 낸다(`2z-1`). 출력: `backend <sha>` / `frontend <sha>`.
#
# 서브트리 해시를 쓰면 `backend/CLAUDE.md` 같은 문서도 코드로 센다 — `2z` 가 그 자리에서 자기 훅에 막혔다.
# 그래서 빌드·테스트 결과를 바꾸는 경로만 고른다. 경로를 더할 때 여기 한 곳만 고친다.
set -uo pipefail
cd "$(dirname "$0")/.."
tree=${1:-HEAD}
lane() {
  local name=$1; shift
  for p in "$@"; do printf '%s %s\n' "$p" "$(git rev-parse -q --verify "$tree:$p" 2>/dev/null || echo -)"; done \
    | git hash-object --stdin | sed "s/^/$name /"
}
# `backend/config` 는 SpotBugs 제외 목록이다(`점검 K`). **빌드 결과를 바꾼다** —
# 제외를 넓히면 진짜 검출이 숨는데, 여기 없으면 도장이 안 바뀌어 Stop hook 이 안 막는다.
#
# **다만 빠른 레인은 SpotBugs 를 안 돈다**(`gradlew test` 에 안 달려 있다). 그래서 이 경로가
# 실제로 막는 것은 **push 앞 `--full`** 이고, 그 전까지는 「도장을 다시 받아야 한다」까지다.
lane backend  backend/src backend/config backend/build.gradle.kts backend/settings.gradle.kts backend/gradle backend/gradlew backend/gradle.properties
lane frontend frontend/src frontend/e2e frontend/package.json frontend/package-lock.json frontend/tsconfig.json \
              frontend/next.config.ts frontend/eslint.config.mjs frontend/vitest.config.ts frontend/vitest.setup.ts \
              frontend/playwright.config.ts frontend/postcss.config.mjs
