#!/usr/bin/env bash
# Docker 데몬이 안 떠 있으면 Docker Desktop 을 켜고 뜰 때까지 기다린다(밤샘 플랜, 2026-09-26).
# `verify.sh`·`gate-probe.sh` 가 컨테이너를 쓰기 전에 부른다 — 사용자가 없는 밤에 「Docker Desktop 을 켜고 다시 돌린다」로 서지 않게.
# Windows 전용이다. 리눅스·CI 러너는 데몬이 원래 떠 있어 `docker info` 한 번으로 끝난다.
# ProjectShop 에는 이 파일이 없다 — 그쪽 세션이 손으로 친 명령(Start-Process + 5초 간격 대기)을 옮기되, 경로를 안 박는
# `docker desktop start`(Desktop CLI)를 먼저 쓰고 CLI 가 없을 때만 exe 를 부른다.
set -uo pipefail

docker info >/dev/null 2>&1 && exit 0

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) ;;
  *) echo "Docker 데몬이 안 떠 있다 — 이 환경에서는 손으로 띄운다." >&2; exit 1 ;;
esac

if docker desktop version >/dev/null 2>&1; then
  # `-d` 로 기다리지 않는다 — 기본은 끝날 때까지 시간 제한 없이 기다려서, Desktop 이 약관·업데이트 창에서 멈추면
  # 여기서 끝없이 선다(마무리 13차 독립 리뷰). 기다림은 아래 3분 상한이 든다.
  docker desktop start -d >/dev/null 2>&1 || true
else
  powershell -NoProfile -Command 'Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"'
fi
echo "Docker Desktop 을 켰다 — 데몬을 기다린다(최대 3분)"
for i in $(seq 1 36); do
  if docker info >/dev/null 2>&1; then
    echo "데몬이 떴다(약 $((i * 5))초)"
    exit 0
  fi
  sleep 5
done
echo "3분이 지나도 데몬이 안 떴다 — Docker Desktop 창을 본다." >&2
exit 1
