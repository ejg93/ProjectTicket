#!/usr/bin/env bash
# 클러스터 안의 앱이 답하나(`36`). port-forward 는 파드 **하나**에 붙는다 — 대수 나누기는 여기서 안 본다(`38` 이 클러스터 안에서 잰다).
set -uo pipefail

PORT=18080
kubectl -n ticket port-forward svc/app "$PORT:8080" >/dev/null 2>&1 &
pf=$!
trap 'kill $pf 2>/dev/null' EXIT

app_ok=0
for _ in $(seq 1 20); do
  if body=$(curl -sf "localhost:$PORT/api/health"); then
    echo "$body"
    app_ok=1
    break
  fi
  sleep 1
done
[ "$app_ok" = 1 ] || { echo "앱이 답하지 않는다 — kubectl -n ticket get pods 와 logs 를 본다" >&2; exit 1; }

# 화면(`36a`) — NodePort 를 kind 가 호스트 3000 으로 연다. `/events` 는 서버 컴포넌트가 앱을 직접 부른다.
for path in /login /events; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "localhost:3000$path")
  echo "web $path $code"
  [ "$code" = 200 ] || { echo "화면이 $path 에 200 을 안 준다 — kubectl -n ticket logs deploy/web" >&2; exit 1; }
done
