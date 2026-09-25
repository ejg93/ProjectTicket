#!/usr/bin/env bash
# PreToolUse(Bash·PowerShell). `git push` 를 잡는다 — main 직접 금지, 현재 가지가 main 이면 금지,
# 바뀐 레인마다 HEAD 지문의 `full` 도장이 있어야 한다(`/verify`).
. "$(dirname "$0")/_tool-input.sh"
c=$(tool_field command)

if printf '%s\n' "$c" | grep -qE '(^|[;&|])[[:space:]]*git push([[:space:]]+-[-A-Za-z=]+)*([[:space:]]+[A-Za-z0-9_./-]+)?[[:space:]]+([A-Za-z0-9_./-]+:)?main([[:space:]]|$)'; then
  echo 'main 에 직접 안 민다(CLAUDE.md 「재개 프로토콜」 6번). work/<날짜> 가지에 밀고 마무리가 PR 을 연다.' >&2
  exit 2
fi

printf '%s\n' "$c" | grep -qE '(^|[;&|])[[:space:]]*git push([[:space:]]|$)' || exit 0

if [ "$(git rev-parse --abbrev-ref HEAD 2>/dev/null)" = main ]; then
  echo '현재 가지가 main 이다. work/<날짜> 가지를 따고 민다(CLAUDE.md 「재개 프로토콜」 6번).' >&2
  exit 2
fi

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
git rev-parse -q --verify origin/main >/dev/null 2>&1 || exit 0
st="$(git rev-parse --git-dir)/verify-stamp"
head=$(bash scripts/verify-fingerprint.sh HEAD)
main=$(bash scripts/verify-fingerprint.sh origin/main)
fail=''
for d in backend frontend tools; do
  h=$(echo "$head" | grep "^$d ")
  [ "$h" = "$(echo "$main" | grep "^$d ")" ] && continue
  grep -qx "$h full" "$st" 2>/dev/null || fail="$fail $d"
done
if [ -n "$fail" ]; then
  echo "full 도장이 없다:$fail — push 앞엔 bash scripts/verify.sh --full 이 HEAD 에서 초록이어야 한다(/verify)." >&2
  exit 2
fi
exit 0
