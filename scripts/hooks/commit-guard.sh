#!/usr/bin/env bash
# PreToolUse(Bash·PowerShell). `work/*` 가지에서 `git commit` 을 잡는다 — 작업 트리 지문이 도장에 없으면 막는다(`B0-1`).
# WIP 는 `wip/<청크>` 가지로 — 빨간 트리가 번들 가지에 오르면 뒤 청크 verify 가 다 빨개진다.
. "$(dirname "$0")/_tool-input.sh"
c=$(tool_field command)

printf '%s\n' "$c" | grep -qE '(^|[;&|])[[:space:]]*git commit([[:space:]]|$)' || exit 0
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
case "$(git rev-parse --abbrev-ref HEAD 2>/dev/null)" in work/*) ;; *) exit 0 ;; esac
git rev-parse -q --verify origin/main >/dev/null 2>&1 || exit 0

st="$(git rev-parse --git-dir)/verify-stamp"
# 작업 트리(커밋 안 된 것 포함)의 트리 해시 — verify.sh 와 같은 방법.
tmp=$(mktemp)
GIT_INDEX_FILE="$tmp" git read-tree HEAD
GIT_INDEX_FILE="$tmp" git add -A . 2>/dev/null
tree=$(GIT_INDEX_FILE="$tmp" git write-tree)
rm -f "$tmp"

work=$(bash scripts/verify-fingerprint.sh "$tree")
main=$(bash scripts/verify-fingerprint.sh origin/main)
fail=''
for d in backend frontend tools; do
  h=$(echo "$work" | grep "^$d ")
  [ "$h" = "$(echo "$main" | grep "^$d ")" ] && continue
  grep -q "^$h " "$st" 2>/dev/null || fail="$fail $d"
done
[ -z "$fail" ] && exit 0
echo "도장 없는 커밋은 work/* 에 안 올린다:$fail — 이 작업 트리에서 bash scripts/verify.sh 가 초록이어야 한다. 두 번 고쳐도 빨가면 wip/<청크> 가지에 커밋한다(CLAUDE.md 「번들 모드」)." >&2
exit 2
