#!/usr/bin/env bash
# 롤링 재시작 동안 5xx·연결 오류가 나나 잰다(`38`). 결과는 `doc/notes/rollout.md` 에 옮겨 적는다.
# k6 를 **클러스터 안 Job** 으로 돌린다 — port-forward 는 파드 하나에 붙어서 롤링을 못 본다.
#
#   bash scripts/k8s-rollout-test.sh               # 부하 20초 뒤 rollout restart
#   bash scripts/k8s-rollout-test.sh --no-restart  # 판 1 — 롤링 없이 같은 부하
#   VUS=300 HOLD=150s bash scripts/k8s-rollout-test.sh
#
# 앞에 `bash scripts/k8s-up.sh` 가 돌아 있어야 한다(데모 시드는 `local` 프로필).
set -euo pipefail
cd "$(dirname "$0")/.."

NS=ticket
K6_IMAGE=grafana/k6:2.2.0
restart=1
[ "${1:-}" = "--no-restart" ] && restart=0
VUS=${VUS:-100}
HOLD=${HOLD:-150s}

# 부하 계정 300 + 열린 회차의 좌석 시작값. 첫 줄이 첫 열린 회차다.
row=$(kubectl -n "$NS" exec -i db-0 -- psql -qAt -U ticket -d ticket < load/prepare.sql | head -1)
IFS='|' read -r perf first seats <<<"$row"
[ -n "${seats:-}" ] || { echo "열린 회차를 못 읽었다 — 데모 시드가 돌았나(local 프로필): '$row'" >&2; exit 1; }
echo "== 회차 $perf · 좌석 $first 부터 $seats 석 · VUS $VUS · HOLD $HOLD · 재시작 $restart"

kubectl -n "$NS" create configmap k6-script --from-file=seat-rush.js=load/seat-rush.js --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NS" delete job k6 --ignore-not-found --wait >/dev/null
kubectl -n "$NS" apply -f - >/dev/null <<YAML
apiVersion: batch/v1
kind: Job
metadata:
  name: k6
  namespace: $NS
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: $K6_IMAGE
          args: ["run", "/scripts/seat-rush.js"]
          env:
            - { name: BASE_URL, value: "http://app:8080" }
            - { name: PERFORMANCE_ID, value: "$perf" }
            - { name: FIRST_SEAT_ID, value: "$first" }
            - { name: SEATS, value: "$seats" }
            - { name: VUS, value: "$VUS" }
            - { name: HOLD, value: "$HOLD" }
          volumeMounts:
            - { name: script, mountPath: /scripts }
      volumes:
        - name: script
          configMap:
            name: k6-script
YAML

# 이미지를 처음 받으면 시간이 걸린다 — 부하가 실제로 시작된 뒤부터 센다.
kubectl -n "$NS" wait --for=condition=ready pod -l job-name=k6 --timeout=300s >/dev/null

# 대수를 5초마다 적는다 — HPA 가 몇 대까지 늘렸나.
samples=$(mktemp)
( while true; do kubectl -n "$NS" get deployment app -o jsonpath='{.status.replicas}' 2>/dev/null; echo; sleep 5; done ) > "$samples" &
sampler=$!
trap 'kill $sampler 2>/dev/null || true; rm -f "$samples"' EXIT

if [ "$restart" = 1 ]; then
  sleep 20
  echo "== rollout restart"
  kubectl -n "$NS" rollout restart deployment/app >/dev/null
  kubectl -n "$NS" rollout status deployment/app --timeout=600s
fi

# 문턱(`rate==0`)을 넘으면 k6 가 0 이 아닌 코드로 끝나 Job 이 Failed 다 — 그것도 결과라 둘 다 기다린다.
for _ in $(seq 1 180); do
  done_=$(kubectl -n "$NS" get job k6 -o jsonpath='{.status.succeeded}{.status.failed}')
  [ -n "$done_" ] && break
  sleep 5
done

echo "== k6 요약"
kubectl -n "$NS" logs job/k6 | grep -E "http_req_failed|http_req_duration|hold_latency_ms|seats_won|seats_taken|gate_rejected|checks" || true
echo "== 앱 대수 최대: $(sort -n "$samples" | tail -1) · Job: $(kubectl -n "$NS" get job k6 -o jsonpath='{.status.conditions[0].type}')"

# 판의 판정(`54`) — Job 이 Failed 여도 exit 0 이면 롤링 중 5xx 가 나도 초록으로 끝난다
[ "$(kubectl -n "$NS" get job k6 -o jsonpath='{.status.succeeded}')" = 1 ] \
  || { echo "k6 Job 이 성공이 아니다 — 롤링 중 5xx·연결 오류가 났거나(rate==0) 시간 안에 안 끝났다" >&2; exit 1; }
