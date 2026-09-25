#!/usr/bin/env bash
# 건드린 것이 무엇을 돌릴지 정한다(`/verify`). `origin/main` 대비 backend 지문이 다르면 backend 레인,
# frontend 지문이 다르면 frontend 레인. 지문은 `verify-fingerprint.sh` 가 낸다(코드·빌드 파일만).
#
# 단계가 둘이다. 기본은 빠른 도장 — backend `gradlew test`(컨테이너 없음), frontend `tsc`·lint·test.
# `--full` 은 backend `gradlew build`(Testcontainers 레인 포함), frontend `next build`·lint·test(+ Playwright 가 있으면 e2e).
# 청크를 닫을 땐 빠른 도장이면 되고(Stop hook·commit hook), push 앞엔 full 이어야 한다(push hook).
# 빠른 단계라도 origin/main 에 없는 새 `V*` 가 있으면 backend 는 `integrationTest` 까지 돈다(`B0`).
# Docker 가 없으면 그 레인은 `gradlew test` 만 돌고 도장이 `fast-nodb` 다 — 번들이 서지 않게(`B0-1`).
#
# 통과하면 `.git/verify-stamp` 에 「레인 지문 단계」를 적는다.
# 도장이 같은 지문을 같은 단계 이상으로 이미 찍어 뒀으면 그 레인은 안 돈다(`B0-1`) —
# frontend 빠른 레인이 109초라 backend 청크마다 다시 돌면 번들에 9분이 붙는다.
set -uo pipefail
cd "$(dirname "$0")/.."

level=fast
[ "${1:-}" = "--full" ] && level=full

# 이 환경의 JAVA_HOME 은 JDK 11 이라 Gradle 이 안 뜬다(CLAUDE.md). 리눅스 러너는 그대로.
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) export JAVA_HOME="C:/Program Files/Java/jdk-25" ;; esac

git rev-parse -q --verify origin/main >/dev/null || { echo "origin/main 이 없다 — 기준이 없어서 못 잰다"; exit 1; }

# 작업 트리(커밋 안 된 것 포함)의 트리 해시. 임시 인덱스라 진짜 인덱스를 안 건드린다.
tmp=$(mktemp); trap 'rm -f "$tmp"' EXIT
GIT_INDEX_FILE="$tmp" git read-tree HEAD
GIT_INDEX_FILE="$tmp" git add -A . 2>/dev/null
tree=$(GIT_INDEX_FILE="$tmp" git write-tree)

fp_work=$(bash scripts/verify-fingerprint.sh "$tree"); fp_main=$(bash scripts/verify-fingerprint.sh origin/main)
changed() { [ "$(echo "$fp_work" | grep "^$1 ")" != "$(echo "$fp_main" | grep "^$1 ")" ]; }
fp_of() { echo "$fp_work" | grep "^$1 " | cut -d' ' -f2; }
st="$(git rev-parse --git-dir)/verify-stamp"
# 도장이 이 레인의 지금 지문을 요청한 단계 이상으로 찍어 뒀나. 찍어 뒀으면 그 단계를 낸다.
stamped() {
  local lvl; lvl=$(grep "^$1 $(fp_of "$1") " "$st" 2>/dev/null | cut -d' ' -f3) || return 1
  [ -n "$lvl" ] || return 1
  if [ "$lvl" = full ] || { [ "$level" = fast ] && [ "$lvl" = fast ]; }; then echo "$lvl"; return 0; fi
  return 1
}
docker_up() { docker info >/dev/null 2>&1; }

ran=0; ok=1; lv_backend=; lv_frontend=
if changed backend && [ -d backend ]; then
  if lv_backend=$(stamped backend); then
    echo "== backend: 같은 지문을 $lv_backend 로 찍어 뒀다 → 건너뜀"
  else
    ran=1; lv_backend=$level
    if [ "$level" = full ]; then
      # Docker 를 먼저 본다. 안 떠 있으면 컨테이너 레인이 전부 FAILED 로 뜨고 진짜 원인은 XML 리포트를 파야 나온다.
      docker_up || { echo "Docker 가 안 떴다. 느린 레인은 컨테이너를 띄운다 — Docker Desktop 을 켜고 다시 돌린다."; exit 1; }
      echo "== backend 지문이 origin/main 과 다르다 → ./gradlew build (두 레인)"
      (cd backend && ./gradlew build -q) || ok=0
    elif [ -n "$(git diff-tree -r --name-only --diff-filter=A origin/main "$tree" -- backend/src/main/resources/db/migration)" ]; then
      # 새 `V*` 가 있으면 컨테이너 레인도 같이 돈다. 빠른 레인만 돌리면 열거형·길이 대조(`integrationTest`)가
      # 번들 끝 `--full` 에서 처음 빨개진다 — ProjectShop 번들 A 가 그랬다(`B0`).
      if docker_up; then
        echo "== backend 지문이 다르고 새 V* 가 있다 → ./gradlew test integrationTest (빠른 레인 + 컨테이너 레인)"
        (cd backend && ./gradlew test integrationTest -q) || ok=0
      else
        # 막지 않는다 — 번들이 서는 값이 누수보다 크다(사용자 결정 2026-09-25). 도장이 fast-nodb 라 push hook 은 안 받는다.
        echo "== 새 V* 가 있는데 Docker 가 안 떴다 → ./gradlew test 만. 컨테이너 레인은 번들 끝 --full 이 돈다(도장 fast-nodb)"
        lv_backend=fast-nodb
        (cd backend && ./gradlew test -q) || ok=0
      fi
    else
      echo "== backend 지문이 origin/main 과 다르다 → ./gradlew test (빠른 레인)"
      (cd backend && ./gradlew test -q) || ok=0
    fi
  fi
fi
if changed frontend && [ -d frontend ]; then
  if lv_frontend=$(stamped frontend); then
    echo "== frontend: 같은 지문을 $lv_frontend 로 찍어 뒀다 → 건너뜀"
  else
    ran=1; lv_frontend=$level
    if [ "$level" = full ]; then
      echo "== frontend 지문이 origin/main 과 다르다 → next build · lint · test"
      (cd frontend && npm run build >/dev/null && npm run lint && npm test 2>&1 | tail -4) || ok=0
      # e2e 는 실물이 있을 때만 — Playwright 와 `e2e` 스크립트(`46b`). 있으면 백엔드가 떠 있어야 한다.
      if [ -x frontend/node_modules/.bin/playwright ] && grep -q '"e2e"' frontend/package.json; then
        if curl -sf localhost:8080/api/health >/dev/null; then
          echo "== Playwright 있음 → npm run e2e"
          (cd frontend && npm run e2e) || ok=0
        else
          echo "Playwright 가 있는데 백엔드가 안 떠 있다 — local 로 띄우고 다시 돌린다(/verify)."; ok=0
        fi
      else
        echo "== e2e 건너뜀 — Playwright 가 없다(46b 가 들인다)"
      fi
    else
      echo "== frontend 지문이 origin/main 과 다르다 → tsc --noEmit · lint · test"
      (cd frontend && npx tsc --noEmit && npm run lint && npm test 2>&1 | tail -4) || ok=0
    fi
  fi
fi
[ "$ran" -eq 0 ] && echo "돌릴 것이 없다 — 지문이 origin/main 과 같거나 도장이 이미 있다"
[ "$ok" -eq 1 ] || { echo "빨갛다 — 도장을 안 찍는다"; exit 1; }

# 안 돈 레인(origin/main 과 같은 것)은 full 로 적는다 — 돌릴 것이 없어서다. 건너뛴 레인은 도장의 단계를 그대로 둔다.
for d in backend frontend; do
  h=$(fp_of "$d")
  if changed "$d"; then eval "echo \"$d $h \$lv_$d\""; else echo "$d $h full"; fi
done > "$st"
echo "초록($level). 도장: $st"
