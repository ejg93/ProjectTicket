#!/usr/bin/env bash
# 변이 대상 소스를 고쳤으면 PIT 를 돌고 살아남은 변이를 기준선과 맞댄다(`I14-2`, `testing-strategy.md` 「레인」 변이 행).
#
# 대상은 `backend/build.gradle.kts` 의 `mutationTargets` 다 — 클래스 이름에서 소스 파일을 찾는다(파일 이름과 클래스 이름이 갈린다:
# `OrderBy` 는 `Paging.kt`, `CardNumbers` 는 `CardNumber.kt`). 그 파일이 origin/main 대비 바뀌었을 때만 돈다 — 한 판 20초 안쪽.
#
# 기준선은 최신 노트 `doc/notes/mutation-*.md` 의 처분표다. 살아남거나 덮이지 않은 변이의 자리(`클래스.메서드:줄`)가
# 노트에 백틱으로 없으면 빨강이다. **수 문턱이 아니다**(`D8` 「문턱이 없다」) — 새로 산 변이는 시험을 더하거나 노트에 처분을 적는다.
# 줄이 밀려도 자리가 바뀌어 빨강이다. 그때는 노트의 자리를 고쳐 적는다 — 처분이 그 줄을 다시 본 기록이다.
#
# 인자: 비교할 트리(기본 HEAD). `verify.sh` 의 backend 레인이 빠른 레인 초록 뒤에 부른다.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
tree=${1:-HEAD}
src=backend/src/main/kotlin

targets=$(sed -n '/^val mutationTargets/,/")$/p' backend/build.gradle.kts \
  | grep -oE '"com\.projectticket\.ticket(\.[a-z]+)*\.[A-Z][A-Za-z]*' | tr -d '"' | sort -u)
[ -n "$targets" ] || { echo "mutationTargets 를 못 읽었다 — build.gradle.kts 꼴이 바뀌었다"; exit 1; }

files=()
for fq in $targets; do
  pkg=${fq%.*}; name=${fq##*.}
  f=$(grep -lE "^(internal |data )?(class|object) $name\b" "$src/${pkg//.//}"/*.kt 2>/dev/null | head -1 || true)
  [ -n "$f" ] || { echo "변이 대상 $fq 의 소스를 $src/${pkg//.//} 에서 못 찾았다"; exit 1; }
  files+=("$f")
done

changed=$(git diff-tree -r --name-only origin/main "$tree" -- "${files[@]}")
[ -n "$changed" ] || exit 0

echo "== 변이 대상을 고쳤다($(echo "$changed" | xargs -n1 basename | sort -u | tr '\n' ' ')) → ./gradlew mutationTest"
log=backend/build/mutation-gate.log
mkdir -p backend/build
(cd backend && ./gradlew mutationTest -q) > "$log" 2>&1 || { tail -20 "$log"; echo "mutationTest 가 빨갛다 — 전체는 $log"; exit 1; }

xml=backend/build/reports/pitest/mutations.xml
total=$(grep -c "<mutation " "$xml" || true)
[ "$total" -gt 0 ] || { echo "$xml 에서 변이를 못 읽었다 — PIT 출력 꼴이 바뀌었다"; exit 1; }

note=$(find doc/notes -name 'mutation-*.md' | sort | tail -1)
[ -n "$note" ] || { echo "기준선 노트 doc/notes/mutation-*.md 가 없다"; exit 1; }

# `<mutatedClass>패키지.Outer$Inner</mutatedClass><mutatedMethod>m</mutatedMethod>…<lineNumber>n</lineNumber>` → `Outer.Inner.m:n`
spots=$(grep -E "status='(SURVIVED|NO_COVERAGE)'" "$xml" \
  | sed -E 's#.*<mutatedClass>([^<]*)</mutatedClass><mutatedMethod>([^<]*)</mutatedMethod>.*<lineNumber>([0-9]+)</lineNumber>.*#\1.\2:\3#' \
  | sed -E 's/^([a-z]+\.)+//' | tr '$' '.' | sort -u)

missing=()
while IFS= read -r spot; do
  [ -z "$spot" ] && continue
  grep -qF "\`$spot\`" "$note" || missing+=("$spot")
done <<< "$spots"

if [ "${#missing[@]}" -gt 0 ]; then
  printf '노트(%s) 처분표 밖에서 산 변이:\n' "$note"; printf '  %s\n' "${missing[@]}"
  echo "시험을 더해 죽이거나 노트에 자리와 처분을 적는다 — 리포트: backend/build/reports/pitest/index.html"
  exit 1
fi
echo "== 변이 $total — 산 것·덮이지 않은 것은 전부 $note 처분표 안"
