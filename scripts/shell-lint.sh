#!/usr/bin/env bash
# 셸 스크립트의 버그를 ShellCheck 로 막는다(`G13`). 문턱은 warning 이상 — info·style 은 취향이라 안 막는다(사용자 결정 2026-10-21).
# verify.sh tools 레인과 CI docs 잡이 같이 부른다. 판은 stack.md 「ShellCheck」.
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

sc=$(command -v shellcheck || true)
# winget 은 패키지 폴더를 사용자 PATH 에 넣는다 — 설치 전에 연 셸은 그 PATH 를 모른다
if [ -z "$sc" ] && [ -n "${LOCALAPPDATA:-}" ]; then
  for w in "$(cygpath -u "$LOCALAPPDATA" 2>/dev/null)"/Microsoft/WinGet/Packages/koalaman.shellcheck_*/shellcheck.exe; do
    [ -x "$w" ] && sc=$w
  done
fi
[ -n "$sc" ] || { echo "shellcheck 가 없다 — winget install --exact --id koalaman.shellcheck --version 0.11.0 (stack.md)" >&2; exit 1; }

"$sc" -S warning scripts/*.sh scripts/hooks/*.sh
