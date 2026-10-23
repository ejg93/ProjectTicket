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
cd "$(dirname "$0")/.." || exit 1

level=fast
[ "${1:-}" = "--full" ] && level=full

# 이 환경의 JAVA_HOME 은 JDK 11 이라 Gradle 이 안 뜬다(CLAUDE.md). 리눅스 러너는 그대로.
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) export JAVA_HOME="C:/Program Files/Java/jdk-25" ;; esac

git rev-parse -q --verify origin/main >/dev/null || { echo "origin/main 이 없다 — 기준이 없어서 못 잰다"; exit 1; }

# `--full` 의 컨테이너 레인이 남긴 Testcontainers 컨테이너를 지운다 — 재사용(`~/.testcontainers.properties`)으로 판마다 남는다(`G20`).
# **이 저장소 것만** 지운다(`G24`, 사용자 결정 (다)) — `org.testcontainers=true` 로 지우면 같은 Docker 를 쓰는 ProjectShop 의
# 돌고 있는 시험이 Connection refused 로 무너졌다. 라벨은 `PostgresTestBase.Containers.OWNER_LABEL` 과 같아야 한다.
OWNER_LABEL=projectticket
tc_cleanup=0; tmp=; fperr=
tc_remove() {  # 지운 수를 낸다
  local ids; ids=$(docker ps -aq --filter "label=$OWNER_LABEL=true" 2>/dev/null)
  [ -n "$ids" ] || { echo 0; return 0; }
  # shellcheck disable=SC2086 # id 목록을 낱낱으로 넘긴다
  docker rm -f $ids >/dev/null 2>&1
  echo "$ids" | wc -l | tr -d ' '
}
cleanup() {
  rm -f "$tmp" "$fperr"
  # 빨강으로 일찍 끝나도 지운다 — 초록 길은 `build` 직후에 이미 지웠다
  [ "$tc_cleanup" = 1 ] && tc_remove >/dev/null
  return 0
}

# 작업 트리(커밋 안 된 것 포함)의 트리 해시. 임시 인덱스라 진짜 인덱스를 안 건드린다.
tmp=$(mktemp); trap cleanup EXIT
GIT_INDEX_FILE="$tmp" git read-tree HEAD
GIT_INDEX_FILE="$tmp" git add -A . 2>/dev/null
tree=$(GIT_INDEX_FILE="$tmp" git write-tree)

fperr=$(mktemp)
fp_work=$(bash scripts/verify-fingerprint.sh "$tree" 2>"$fperr"); fp_main=$(bash scripts/verify-fingerprint.sh origin/main 2>/dev/null)
# 지문이 비면 아래 `changed()` 가 빈 줄 둘을 「같다」로 읽어 「돌릴 것이 없다」 → 세 레인 full 도장이 된다(`G21`).
for fp in "$fp_work" "$fp_main"; do
  [ "$(printf '%s\n' "$fp" | grep -cE '^(backend|frontend|tools) [0-9a-f]+$')" -eq 3 ] \
    || { echo "지문을 못 냈다 — scripts/verify-fingerprint.sh 가 레인 셋을 안 냈다: ${fp:-(빈 출력)}"; exit 1; }
done
if [ -s "$fperr" ]; then
  echo "지문 목록의 경로가 작업 트리에 없다 — 옮기거나 지웠으면 scripts/verify-fingerprint.sh 의 목록도 고친다:"
  sed 's/^/    /' "$fperr"; exit 1
fi
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
# 안 떠 있으면 켜고 기다린다(`docker-up.sh`, 밤샘 플랜) — 사용자가 없는 밤에 「켜고 다시 돌린다」로 서지 않게.
docker_up() { bash scripts/docker-up.sh; }
# vitest 는 초록이면 끝 네 줄, 빨가면 실패 줄을 20줄까지 — 끝 네 줄에는 무엇이 빨간지가 없다(`G11-1`).
# 실패 줄 모양이 vitest 판과 안 맞으면 끝 40줄로 물러난다.
vitest_run() {
  local out rc=0
  out=$(mktemp)
  (cd frontend && npm test) >"$out" 2>&1 || rc=1
  if [ $rc = 0 ]; then tail -4 "$out"; else grep -E '^ +(FAIL|×|✗) |^[A-Za-z]*Error: ' "$out" | head -20 | grep . || tail -40 "$out"; fi
  rm -f "$out"
  return $rc
}

ran=0; ok=1; lv_backend=; lv_frontend=; lv_tools=
if changed backend && [ -d backend ]; then
  if lv_backend=$(stamped backend); then
    echo "== backend: 같은 지문을 $lv_backend 로 찍어 뒀다 → 건너뜀"
  else
    ran=1; lv_backend=$level
    if [ "$level" = full ]; then
      # Docker 를 먼저 본다. 안 떠 있으면 컨테이너 레인이 전부 FAILED 로 뜨고 진짜 원인은 XML 리포트를 파야 나온다.
      docker_up || { echo "Docker 가 안 떴다. 느린 레인은 컨테이너를 띄운다 — Docker Desktop 을 켜고 다시 돌린다."; exit 1; }
      echo "== backend 지문이 origin/main 과 다르다 → ./gradlew build (두 레인)"
      tc_cleanup=1
      if (cd backend && ./gradlew build -q); then
        # 컨테이너 레인이 돌았으면 우리 라벨이 하나 이상 있다 — 0 이면 라벨이 갈려 정리가 조용히 빈다(`G24`).
        # 빌드가 빨가면 시험 전에 섰을 수 있어 이 판정을 안 한다(정리는 EXIT trap 이 한다)
        removed=$(tc_remove)
        if [ "$removed" -gt 0 ]; then echo "== 이 저장소의 Testcontainers 컨테이너 ${removed}개를 지웠다"
        else echo "이 저장소 라벨($OWNER_LABEL=true)의 컨테이너가 없다 — PostgresTestBase 의 OWNER_LABEL 과 갈렸다"; ok=0; fi
      else
        ok=0
      fi
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
      # e2e 의 백엔드: `BACKEND_ORIGIN`(기본 8080)에 떠 있으면 그것, 없으면 `e2e-local.sh` 가 띄운다(`G19`).
      # 화면의 `/api` 프록시 목적지는 빌드 때 굳어서 **빌드 전에** 정한다 — 늦게 정하면 남의 8080 으로 간다(마무리 24차).
      has_e2e=0; e2e_local=0
      [ -x frontend/node_modules/.bin/playwright ] && grep -q '"e2e"' frontend/package.json && has_e2e=1
      if [ $has_e2e = 1 ] && ! curl -sf "${BACKEND_ORIGIN:-http://localhost:8080}/api/health" >/dev/null; then
        e2e_local=1; export BACKEND_ORIGIN="http://localhost:${E2E_PORT:-8090}"
      fi
      { (cd frontend && npm run build >/dev/null && npm run lint) && vitest_run; } || ok=0
      if [ $has_e2e = 1 ]; then
        if [ $e2e_local = 1 ]; then
          echo "== Playwright 있음, 백엔드 없음 → scripts/e2e-local.sh(빈 DB · $BACKEND_ORIGIN)"
          E2E_BUILT=1 bash scripts/e2e-local.sh || ok=0
        else
          echo "== Playwright 있음 → npm run e2e(${BACKEND_ORIGIN:-http://localhost:8080})"
          (cd frontend && npm run e2e) || ok=0
        fi
      else
        # `46b` 전에는 건너뛰었다. 지금 건너뛰면 e2e 없이 full 도장이 찍힌다(`G21`).
        echo "Playwright 나 \"e2e\" 스크립트가 없다 — cd frontend && npm ci 뒤 다시 돌린다"; ok=0
      fi
    else
      echo "== frontend 지문이 origin/main 과 다르다 → tsc --noEmit · lint · test"
      { (cd frontend && npx tsc --noEmit && npm run lint) && vitest_run; } || ok=0
    fi
  fi
fi
if changed tools; then
  if lv_tools=$(stamped tools); then
    echo "== tools: 같은 지문을 $lv_tools 로 찍어 뒀다 → 건너뜀"
  else
    # 검증 도구 자체(`B0-2`): 셸 문법 · ShellCheck(`G13`) · settings.json 파싱 · doc-lint 전체 · 훅·린트 회귀 시험(`G1`). 빠름·full 이 같다.
    ran=1; lv_tools=$level
    echo "== tools 지문이 origin/main 과 다르다 → bash -n scripts · shellcheck · settings.json 파싱 · doc-lint 전체 · hooks-test"
    { for f in scripts/*.sh scripts/hooks/*.sh; do bash -n "$f" || { echo "문법: $f"; false; }; done; } || ok=0
    node -e 'JSON.parse(require("fs").readFileSync(".claude/settings.json","utf8"))' || { echo "settings.json 이 JSON 이 아니다"; ok=0; }
    bash scripts/doc-lint.sh >/dev/null || ok=0
    bash scripts/hooks-test.sh || ok=0
    bash scripts/shell-lint.sh || ok=0
    # 차트(`37`): lint 와 렌더 ↔ 커밋된 스냅샷. helm 이 없는 기계면 그 둘만 건너뛴다 — CI `backend` 잡이 같은 것을 돈다.
    if command -v helm >/dev/null; then
      helm lint --strict --quiet k8s/chart >/dev/null || { echo "helm lint 가 빨갛다 — helm lint --strict k8s/chart"; ok=0; }
      helm template ticket k8s/chart -n ticket | diff -q - k8s/chart/snapshot.yaml >/dev/null         || { echo "차트 렌더가 스냅샷과 다르다 — bash scripts/helm-snapshot.sh 를 돌리고 커밋한다"; ok=0; }
    else
      echo "== helm 이 없다 — 차트 lint·스냅샷 비교를 건너뛴다(CI backend 잡이 돈다)"
    fi
  fi
fi
[ "$ran" -eq 0 ] && echo "돌릴 것이 없다 — 지문이 origin/main 과 같거나 도장이 이미 있다"
[ "$ok" -eq 1 ] || { echo "빨갛다 — 도장을 안 찍는다"; exit 1; }

# 안 돈 레인(origin/main 과 같은 것)은 full 로 적는다 — 돌릴 것이 없어서다. 건너뛴 레인은 도장의 단계를 그대로 둔다.
for d in backend frontend tools; do
  h=$(fp_of "$d")
  if changed "$d"; then eval "echo \"$d $h \$lv_$d\""; else echo "$d $h full"; fi
done > "$st"
echo "초록($level). 도장: $st"
