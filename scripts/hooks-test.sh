#!/usr/bin/env bash
# 훅 9개와 `doc-lint` 검사를 표 한 장으로 시험한다(`G1`). `verify.sh` tools 레인이 돈다.
# 한 줄이 한 경우다 — 「이름 | 훅 | stdin | 기대 exit | 출력 앞머리」. 앞머리가 빈 칸이면 출력도 비어야 한다.
#
# 저장소를 안 건드린다. `mktemp -d` 에 git 저장소를 새로 만들고(`origin/main` 은 첫 커밋, 가지는 `work/test`)
# `scripts/`·설정·문서 몇을 복사해 넣는다. 훅은 그 사본을 `CLAUDE_PROJECT_DIR` 로 받는다 —
# 도장·지문·가지가 사람 작업 트리를 타지 않게 하려는 것이다(설계 실패 사다리의 `GIT_DIR` 고정).
set -uo pipefail
cd "$(dirname "$0")/.."
root=$(pwd)
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE

fx=$(mktemp -d); trap 'rm -rf "$fx"' EXIT
mkdir -p "$fx/.claude/skills/verify"
cp -r scripts "$fx/"
cp .claude/settings.json "$fx/.claude/"
cp .claude/skills/verify/SKILL.md "$fx/.claude/skills/verify/"
cp CLAUDE.md PLAN.md PROGRESS.md "$fx/"
# `core.autocrlf` 를 끈다 — 켜진 환경에서 `restore` 가 셸 파일을 CRLF 로 되살리면 뒤 경우가 문법으로 죽는다.
g() { git -C "$fx" -c core.autocrlf=false -c user.name=hooks-test -c user.email=hooks-test@localhost -c commit.gpgsign=false "$@"; }
g init -q
g add -A && g commit -qm base
g update-ref refs/remotes/origin/main HEAD
g checkout -qb work/test
# tools 레인 지문을 origin/main 과 다르게 — 도장을 보는 훅 셋이 일을 하는 상태.
echo '# hooks-test' > "$fx/scripts/zz-fixture.sh"
g add -A && g commit -qm tools

fails=0; n=0
check() {  # 이름 기대exit 앞머리 실제exit 출력
  n=$((n + 1))
  if [ "$4" != "$2" ] || { [ -z "$3" ] && [ -n "$5" ]; } || [[ "$5" != "$3"* ]]; then
    fails=$((fails + 1))
    printf '[실패] %s — exit %s(기대 %s), 출력: %s\n' "$1" "$4" "$2" "$(printf '%s' "$5" | head -1 | cut -c1-80)"
  fi
}
hook() {  # 이름 훅 stdin 기대exit 앞머리
  local out rc
  out=$(cd "$fx" && printf '%s' "$3" | CLAUDE_PROJECT_DIR="$fx" bash "$fx/scripts/hooks/$2.sh" 2>&1); rc=$?
  check "$1" "$4" "$5" "$rc" "$out"
}
lint() {  # 이름 기대exit 앞머리 파일…
  local name=$1 want=$2 head=$3 out rc; shift 3
  out=$(bash "$fx/scripts/doc-lint.sh" "$@" 2>&1); rc=$?
  check "$name" "$want" "$head" "$rc" "$out"
}
cmd() { printf '{"tool_input":{"command":"%s"}}' "$1"; }
edit() { printf '{"tool_input":{"file_path":"%s"}}' "$1"; }
stamp() {  # 단계 — 지금 작업 트리의 지문 전부를 그 단계로 찍는다
  (cd "$fx" && bash scripts/verify-fingerprint.sh HEAD | sed "s/\$/ $1/") > "$fx/.git/verify-stamp"
}
restore() { g checkout -q -- . && g clean -qfd; }

# ── 도장 없음
hook 'push main'           push-guard       "$(cmd 'git push origin main')"      2 'main 에 직접 안 민다'
hook 'push 도장 없음'      push-guard       "$(cmd 'git push')"                  2 'full 도장이 없다'
hook 'push 아닌 명령'      push-guard       "$(cmd 'ls')"                        0 ''
hook 'commit 도장 없음'    commit-guard     "$(cmd 'git commit -m x')"           2 '도장 없는 커밋은'
hook 'commit 아닌 git'     commit-guard     "$(cmd 'git status')"                0 ''
hook 'stop 도장 없음'      stop-stamp       '{"stop_hook_active":false}'         2 '검증 도장이 없다'
hook 'stop 재진입'         stop-stamp       '{"stop_hook_active":true}'          0 ''
# ── 빠른 도장
stamp fast
hook 'commit 도장 뒤'      commit-guard     "$(cmd 'git commit -m x')"           0 ''
hook 'stop 도장 뒤'        stop-stamp       '{"stop_hook_active":false}'         0 ''
hook 'push 빠른 도장'      push-guard       "$(cmd 'git push')"                  2 'full 도장이 없다'
# ── full 도장
stamp full
hook 'push full 도장'      push-guard       "$(cmd 'git push')"                  0 ''
# ── 도장과 무관한 훅
hook 'PR base develop'     pr-base-guard    "$(cmd 'gh pr create --base develop')" 2 'PR 의 base 는'
hook 'PR base main'        pr-base-guard    "$(cmd 'gh pr create --base main')"  0 ''
hook 'gradlew 맨몸'        java-home-guard  "$(cmd 'cd backend && ./gradlew test')" 2 'backend 명령 앞에'
hook 'gradlew JAVA_HOME'   java-home-guard  "$(cmd 'JAVA_HOME=C:/jdk ./gradlew test')" 0 ''
hook 'session-state'       session-state    '{}'                                 0 '## 현재 상태'
hook 'stop 깨끗한 트리'    stop-uncommitted '{"stop_hook_active":false}'         0 ''
touch "$fx/dirty.txt"
hook 'stop 더러운 트리'    stop-uncommitted '{"stop_hook_active":false}'         2 '커밋 안 된 작업물'
hook 'stop 더러운 재진입'  stop-uncommitted '{"stop_hook_active":true}'          0 ''
restore

# ── doc-lint — 깨끗하면 0, 위반 하나마다 그 검사의 머리말. 위반은 사본에만 넣고 경우마다 되돌린다.
lint '깨끗한 문서'  0 '이상 없음' CLAUDE.md PLAN.md PROGRESS.md .claude/skills/verify/SKILL.md
hook '편집 CLAUDE.md'      doc-lint-edit    "$(edit "$fx/CLAUDE.md")"           0 ''
hook '편집 kt'             doc-lint-edit    "$(edit "$fx/Foo.kt")"              0 ''
hook 'sed PLAN'            doc-lint-bash    "$(cmd 'sed -i s/a/a/ PLAN.md')"    0 ''
hook 'bash 아닌 편집'      doc-lint-bash    "$(cmd 'ls')"                       0 ''

echo '시험 문장이다. 이것을 확인하세요.' >> "$fx/CLAUDE.md"
lint '존댓말'       1 '[존댓말]' CLAUDE.md
# 훅이 준 경로를 범위 모드가 실제로 알아듣나 — 못 알아들으면 범위 밖이라 조용히 통과한다. 윈도는 `C:\…` 꼴로 준다.
p="$fx/CLAUDE.md"; w=$(cd "$fx" && pwd -W 2>/dev/null) && p="${w//\//\\\\}\\\\CLAUDE.md"
hook '편집 훅 존댓말'      doc-lint-edit    "$(edit "$p")"                      2 '[존댓말]'
hook 'sed 훅 존댓말'       doc-lint-bash    "$(cmd 'sed -i s/a/a/ CLAUDE.md')"  2 '[존댓말]'
restore

printf '| 가 | 이 셀은 서른 글자를 넘기는 시험용 중복 셀 문장이다 |\n| 나 | 이 셀은 서른 글자를 넘기는 시험용 중복 셀 문장이다 |\n' >> "$fx/CLAUDE.md"
lint '표 셀 중복'   1 '[중복 셀]' CLAUDE.md
restore

awk '/^## 이 계획을 고칠 때/{print "| ZZ | 시험 행 | **축**: 시험. **강제 지점**: 시험. **번호 절차**: ① 시험. **닫힘**: ① | 없음 |"} {print}' \
  "$fx/PLAN.md" > "$fx/PLAN.tmp" && mv "$fx/PLAN.tmp" "$fx/PLAN.md"
lint '설계 행'      1 '[설계 행 누락]' PLAN.md
restore

awk '/^## 이력/{on=1} on && !done && /^\| [0-9]{4}-[0-9]{2}-[0-9]{2} \| [^|]*\| 완료/{sub(/\|[^|]*\|[[:space:]]*$/, "|  |"); done=1} {print}' \
  "$fx/PROGRESS.md" > "$fx/PROGRESS.tmp" && mv "$fx/PROGRESS.tmp" "$fx/PROGRESS.md"
lint '이력 해시'    1 '[이력 해시 빈 칸]' PROGRESS.md
restore

printf '\xff\xfe\n' >> "$fx/.claude/skills/verify/SKILL.md"
lint 'UTF-8'        1 '[UTF-8 깨짐]' .claude/skills/verify/SKILL.md
restore

[ "$fails" -eq 0 ] && { echo "훅·린트 시험 $n 경우 초록"; exit 0; }
echo "훅·린트 시험 $n 경우 중 $fails 빨강"; exit 1
