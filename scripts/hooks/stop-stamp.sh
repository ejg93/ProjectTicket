#!/usr/bin/env bash
# Stop. HEAD 의 코드가 origin/main 과 다른데 그 지문으로 `verify.sh` 가 돈 적이 없으면 세션을 못 닫는다(`/verify`).
. "$(dirname "$0")/_tool-input.sh"
[ "$(hook_field stop_hook_active)" = true ] && exit 0

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
git rev-parse -q --verify origin/main >/dev/null 2>&1 || exit 0
st="$(git rev-parse --git-dir)/verify-stamp"
head=$(bash scripts/verify-fingerprint.sh HEAD)
main=$(bash scripts/verify-fingerprint.sh origin/main)
fail=''
for d in backend frontend tools; do
  h=$(echo "$head" | grep "^$d ")
  [ "$h" = "$(echo "$main" | grep "^$d ")" ] && continue
  [ "$h" = "$(grep "^$d " "$st" 2>/dev/null | cut -d' ' -f1,2)" ] || fail="$fail $d"
done
[ -z "$fail" ] && exit 0
echo "검증 도장이 없다:$fail — HEAD 의 코드가 origin/main 과 다른데 bash scripts/verify.sh 가 그 상태에서 돈 적이 없다(/verify). 돌리고 나서 멈춘다." >&2
exit 2
