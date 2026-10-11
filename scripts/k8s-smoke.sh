#!/usr/bin/env bash
# 클러스터 안의 앱이 답하나(`36`). port-forward 는 파드 **하나**에 붙는다 — 대수 나누기는 여기서 안 본다(`38` 이 클러스터 안에서 잰다).
set -uo pipefail

PORT=18080
kubectl -n ticket port-forward svc/app "$PORT:8080" >/dev/null 2>&1 &
pf=$!
trap 'kill $pf 2>/dev/null' EXIT

for _ in $(seq 1 20); do
  if body=$(curl -sf "localhost:$PORT/api/health"); then
    echo "$body"
    exit 0
  fi
  sleep 1
done
echo "앱이 답하지 않는다 — kubectl -n ticket get pods 와 logs 를 본다" >&2
exit 1
