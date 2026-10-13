#!/usr/bin/env bash
# SessionStart. `PROGRESS.md` 「현재 상태」 절을 그대로 컨텍스트에 넣는다 — 그 블록이 단일 진실이다(CLAUDE.md 「재개 프로토콜」).
# 뒤에 `main` 의 마지막 CI 결론을 한 줄 붙인다(`I9-5`). 필수가 아닌 잡(`audit`)은 빨개도 머지를 안 막아서
# 이 줄이 아니면 아무도 못 본다. `gh` 가 없거나 오프라인·시간 초과·빈 결과면 아무것도 안 찍는다.
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
sed -n '/^## 현재 상태/,/^## 이력/p' PROGRESS.md | sed '$d'

GH=${GH:-gh}
command -v "$GH" >/dev/null 2>&1 || exit 0
# 호출 하나에 6초 — 둘을 합쳐도 훅 상한(`settings.json` 15초) 안이다.
run=$(timeout 6 "$GH" run list --branch main --workflow ci.yml --limit 1 \
  --json databaseId,conclusion,headSha \
  --jq '.[0] | "\(.databaseId)|\(.conclusion)|\(.headSha)"' 2>/dev/null) || exit 0
IFS="|" read -r id conclusion sha <<<"$run"
# 실행이 하나도 없으면 `.[0]` 이 null 이라 세 칸이 `null` 이다 — 빈 결과와 같이 무음.
[ -n "${sha:-}" ] && [ "$sha" != null ] || exit 0
sha=${sha:0:7}

echo
case "$conclusion" in
  success) echo "\`main\` CI 초록(\`$sha\`)" ;;
  ''|null) echo "\`main\` CI 도는 중(\`$sha\`) — 결론은 다음 세션이 찍는다" ;;
  *)
    jobs=$(timeout 6 "$GH" run view "$id" --json jobs \
      --jq '[.jobs[] | select(.conclusion == "failure") | .name] | join(", ")' 2>/dev/null)
    echo "**\`main\` CI 빨강**(\`${jobs:-$conclusion}\`) — \`$sha\`. 「무엇부터」 1순위"
    ;;
esac
exit 0
