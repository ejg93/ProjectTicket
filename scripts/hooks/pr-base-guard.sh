#!/usr/bin/env bash
# PreToolUse(Bash·PowerShell). `gh pr create` 의 `--base` 가 main 이 아니면 막는다 —
# 쌓아 올리면 아래가 머지될 때 GitHub 이 base 없는 PR 을 닫는다.
export PATH="$PATH:/c/Program Files/GitHub CLI"
. "$(dirname "$0")/_tool-input.sh"
c=$(tool_field command)

printf '%s\n' "$c" | grep -qE '(^|[;&|])[[:space:]]*gh pr create' || exit 0
b=$(printf '%s\n' "$c" | sed -n 's/.*--base[= ][= ]*\([A-Za-z0-9._/-][A-Za-z0-9._/-]*\).*/\1/p' | head -1)
if [ -n "$b" ] && [ "$b" != main ]; then
  echo 'PR 의 base 는 언제나 main 이다. 쌓아 올리면 아래가 머지될 때 GitHub 이 base 없는 PR 을 닫는다.' >&2
  exit 2
fi
exit 0
