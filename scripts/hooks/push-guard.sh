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

# 가지 삭제는 코드를 안 민다 — 도장을 안 본다(`46c` 가 탐침 가지를 지우다 막혔다). `main` 삭제만 막는다.
if printf '%s\n' "$c" | grep -qE '(^|[;&|])[[:space:]]*git push[^;&|]*[[:space:]](--delete|-d)([[:space:]]|$)'; then
  if printf '%s\n' "$c" | grep -qE '(--delete|-d)[[:space:]]+([^;&|]*[[:space:]])?main([[:space:]]|$)'; then
    echo 'main 을 지우지 않는다.' >&2
    exit 2
  fi
  exit 0
fi

if [ "$(git rev-parse --abbrev-ref HEAD 2>/dev/null)" = main ]; then
  echo '현재 가지가 main 이다. work/<날짜> 가지를 따고 민다(CLAUDE.md 「재개 프로토콜」 6번).' >&2
  exit 2
fi

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
git rev-parse -q --verify origin/main >/dev/null 2>&1 || exit 0
st="$(git rev-parse --git-dir)/verify-stamp"
head=$(bash scripts/verify-fingerprint.sh HEAD 2>/dev/null)
main=$(bash scripts/verify-fingerprint.sh origin/main 2>/dev/null)
# 지문이 비면 아래 비교가 빈 줄 둘을 「같다」로 읽어 그냥 지나간다(`G21`) — 레인 셋이 다 있는지 먼저 본다.
for fp in "$head" "$main"; do
  [ "$(printf '%s\n' "$fp" | grep -cE '^(backend|frontend|tools) [0-9a-f]+$')" -eq 3 ] \
    || { echo "지문을 못 냈다(scripts/verify-fingerprint.sh) — 도장을 못 견준다" >&2; exit 2; }
done
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
