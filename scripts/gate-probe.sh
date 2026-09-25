#!/usr/bin/env bash
# 게이트를 일부러 부숴 빨개지는지 본다(`G2`, `quality-gates.md` 「게이트는 부순 증거가 있어야 닫힌다」).
# 쓰는 법: `bash scripts/gate-probe.sh <탐침>…` 또는 `all`. 탐침은 `scripts/probes/<이름>.patch` 다.
#
# 탐침 파일 머리 — `# 명령:` 한 줄(워크트리 루트에서 돈다) · `# 문구:` 한 줄(출력에 들어 있어야 할 것, 빈 칸이면 안 본다).
# 그 아래는 `git diff` 패치다. 패치가 없으면 명령만 돈다(gitleaks — 키를 저장소에 박으면 CI 가 탐침 파일을 문다).
#
# **HEAD 를 부순다** — `git worktree` 에 HEAD 를 풀고 패치를 대므로 커밋 안 된 작업은 안 탄다.
# frontend 명령이면 `node_modules` 를 링크로 빌려 온다(윈도는 junction). 지울 때 링크만 끊고 원본을 안 건드린다.
# 명령이 0 이 아니고 문구가 나오면 「막았다」. 0 이면 **게이트가 안 막은 것** — 그것이 발견이다(설계 실패 사다리).
set -uo pipefail
cd "$(dirname "$0")/.."
root=$(pwd)
[ $# -gt 0 ] || { echo "쓰는 법: bash scripts/gate-probe.sh <탐침>… | all  — 탐침: $(ls scripts/probes | sed 's/\.patch$//' | tr '\n' ' ')"; exit 2; }
if [ "$1" = all ]; then set -- $(ls scripts/probes | sed 's/\.patch$//'); fi

win() { case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) return 0 ;; *) return 1 ;; esac; }
# 이 환경의 기본 JDK 는 11 이라 Gradle 이 안 뜬다(CLAUDE.md). `verify.sh` 와 같은 자리.
win && export JAVA_HOME="C:/Program Files/Java/jdk-25"
# `MSYS_NO_PATHCONV` 가 없으면 Git Bash 가 `/J` 를 `J:\` 로 바꿔 mklink 가 문법 오류로 죽는다(`stack.md`).
link_modules() {
  if win; then MSYS_NO_PATHCONV=1 cmd /c mklink /J "$(cygpath -w "$1/frontend/node_modules")" "$(cygpath -w "$root/frontend/node_modules")" >/dev/null
  else ln -s "$root/frontend/node_modules" "$1/frontend/node_modules"; fi
}
unlink_modules() {  # junction 은 rmdir 이 링크만 지운다. `rm -rf` 는 원본까지 따라갈 수 있다
  [ -e "$1/frontend/node_modules" ] || return 0
  if win; then MSYS_NO_PATHCONV=1 cmd /c rmdir "$(cygpath -w "$1/frontend/node_modules")"; else rm "$1/frontend/node_modules"; fi
}
# **링크가 남은 채로 `git worktree remove --force` 를 부르지 않는다** — Git for Windows 2.45 는 junction 을 따라 들어가
# 진짜 `frontend/node_modules` 를 비운다(마무리 12차 독립 리뷰가 재현했다, `stack.md`). 끊겼는지 보고 나서 지운다.
remove_worktree() {
  unlink_modules "$1"
  if [ -e "$1/frontend/node_modules" ]; then
    echo "링크가 안 끊겼다: $1/frontend/node_modules — 워크트리를 안 지운다. cmd /c rmdir 로 링크를 먼저 끊고 git worktree remove 한다"
    return 1
  fi
  git worktree remove --force "$1" || echo "워크트리를 못 지웠다: $1 — git worktree prune 으로 치운다"
}
wt=
trap '[ -n "$wt" ] && [ -d "$wt" ] && remove_worktree "$wt"' EXIT
trap 'exit 130' INT TERM

bad=0
for name in "$@"; do
  probe="$root/scripts/probes/$name.patch"
  [ -f "$probe" ] || { echo "[$name] 탐침 파일이 없다: $probe"; bad=1; continue; }
  command=$(sed -n 's/^# 명령: //p' "$probe" | head -1)
  expect=$(sed -n 's/^# 문구: //p' "$probe" | head -1)
  # 컨테이너를 쓰는 탐침(gitleaks·컨테이너 레인)은 Docker 부터 — 꺼져 있으면 「다른 이유로 빨강」이 되어 판정이 안 된다.
  case "$command" in *docker*|*integrationTest*)
    bash scripts/docker-up.sh || { echo "[$name] Docker 가 안 뜬다 — 이 탐침은 못 잰다"; bad=1; continue; } ;;
  esac

  wt="$(mktemp -d)/wt"
  git worktree add -q --detach "$wt" HEAD || { echo "[$name] 워크트리를 못 만들었다"; bad=1; continue; }
  if grep -q '^diff --git' "$probe" && ! git -C "$wt" apply "$probe"; then
    echo "[$name] 패치가 안 붙는다 — 대상 파일이 바뀌었으면 탐침을 다시 뜬다"; bad=1
  else
    case "$command" in *frontend*) link_modules "$wt" ;; esac
    start=$SECONDS
    out=$(cd "$wt" && bash -c "$command" 2>&1); rc=$?
    took=$((SECONDS - start))
    if [ "$rc" -eq 0 ]; then
      echo "[$name] 초록 — 게이트가 안 막았다(${took}초). 이것이 발견이다"; bad=1
    elif [ -n "$expect" ] && ! printf '%s' "$out" | grep -qF -- "$expect"; then
      echo "[$name] 빨강인데 다른 이유다 — 「$expect」 가 출력에 없다(${took}초):"; printf '%s\n' "$out" | tail -8 | sed 's/^/    /'; bad=1
    else
      echo "[$name] 막았다 — exit $rc, 「$expect」(${took}초)"
    fi
  fi
  remove_worktree "$wt" || bad=1
  wt=
done
exit "$bad"
