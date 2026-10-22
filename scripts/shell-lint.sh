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
want=$(sed -n 's/^| ShellCheck | \([0-9.]*\) |.*/\1/p' doc/reference/stack.md)
[ -n "$sc" ] || { echo "shellcheck 가 없다 — winget install --exact --id koalaman.shellcheck --version $want (stack.md)" >&2; exit 1; }
# CI 판은 StackVersionConsistencyTest 가 stack.md 와 맞춘다 — 로컬 판은 여기서 맞춘다
got=$("$sc" --version | sed -n 's/^version: //p' | tr -d '\r')
[ "$got" = "$want" ] || { echo "shellcheck $got — stack.md 는 $want 다" >&2; exit 1; }

"$sc" -S warning scripts/*.sh scripts/hooks/*.sh
