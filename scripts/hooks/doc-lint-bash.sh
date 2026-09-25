#!/usr/bin/env bash
# PostToolUse(Bash·PowerShell). 셸 명령으로 문서를 고쳤으면(`sed -i`·리다이렉트·`Set-Content` 등) `doc-lint.sh` 전체를 돌린다.
# 명령에서 파일명을 믿고 뽑을 수 없어 범위 모드를 안 쓴다. `doc-lint` 자체 호출은 건너뛴다.
. "$(dirname "$0")/_tool-input.sh"
c=$(tool_field command)

case "$c" in *doc-lint*) exit 0 ;; esac
if printf '%s\n' "$c" | grep -qE '(^|[;&|])[[:space:]]*(sed -i|tee |cp |mv |Set-Content|Add-Content|Out-File|Copy-Item|Move-Item)|(^|[;&|])[^;&|"]*>>?[[:space:]]*[^&|;[:space:]]*\.md' \
   && printf '%s\n' "$c" | grep -qE 'CLAUDE\.md|AGENTS\.md|PLAN\.md|PROGRESS\.md|doc.{0,4}reference|skills.{0,4}[a-z-]+.{0,4}SKILL'; then
  out=$(bash "${CLAUDE_PROJECT_DIR:-.}/scripts/doc-lint.sh") || { echo "$out" >&2; exit 2; }
fi
exit 0
