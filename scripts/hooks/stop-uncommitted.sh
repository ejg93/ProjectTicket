#!/usr/bin/env bash
# Stop. 커밋 안 된 작업물이 있으면 세션을 못 닫는다 — 청크 하나 = 커밋 하나(CLAUDE.md 「청크 규칙」).
. "$(dirname "$0")/_tool-input.sh"
[ "$(hook_field stop_hook_active)" = true ] && exit 0

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
s=$(git status --porcelain 2>/dev/null)
[ -z "$s" ] && exit 0
printf '커밋 안 된 작업물이 있다 — 청크 하나 = 커밋 하나. 미완이면 wip/<청크> 가지에 커밋한다(CLAUDE.md 「번들 모드」). 버릴 것이면 git stash 로 치운다.\n%s\n' "$s" >&2
exit 2
