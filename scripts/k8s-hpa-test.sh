#!/usr/bin/env bash
# HPA 가 줄 1만 → 선점(`open-rush.js`, 49)을 받으며 몇 대까지, 몇 초 만에 늘어나나 잰다(`52`). 결과는 `doc/notes/load-5.md` 에 옮겨 적는다.
# k6 를 **클러스터 안 Job** 으로 돌린다(`k8s-rollout-test.sh` 와 같은 이유 — port-forward 는 파드 하나에 붙는다). 롤링은 없다.
#
#   bash scripts/k8s-hpa-test.sh                    # VUS 500 · HOLD 660s
#   VUS=1000 bash scripts/k8s-hpa-test.sh           # 대수가 3 에서 안 늘 때의 둘째 판(52 실패 사다리)
#
# 앞에 `bash scripts/k8s-up.sh` 가 돌아 있어야 한다(데모 시드는 `local` 프로필). 표본은 `load/results/hpa-<시각>/` 에 남는다.
set -euo pipefail
cd "$(dirname "$0")/.."

NS=ticket
K6_IMAGE=grafana/k6:2.2.0
VUS=${VUS:-500}
HOLD=${HOLD:-660s}
ACCOUNTS=${ACCOUNTS:-10000}
# k8s 칸(`D13` 「k8s 3~6대」, `54`) — 네 판(`load-5`·`load-6`) 최악 + 20%. `open-rush.js` 의 기본은 compose 셋 칸이다
POLL_P95=${POLL_P95:-350}
HOLD_WON_P95=${HOLD_WON_P95:-680}
STARTED='Started TicketApplicationKt in'
out=load/results/hpa-$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$out"

# 부하 계정 1만 + 열린 회차의 좌석 시작값. 첫 줄이 첫 열린 회차다.
row=$(kubectl -n "$NS" exec -i db-0 -- psql -qAt -U ticket -d ticket < load/prepare.sql | head -1)
IFS='|' read -r perf first seats <<<"$row"
[ -n "${seats:-}" ] || { echo "열린 회차를 못 읽었다 — 데모 시드가 돌았나(local 프로필): '$row'" >&2; exit 1; }
echo "== 회차 $perf · 좌석 $first 부터 $seats 석 · 계정 $ACCOUNTS · VUS $VUS · HOLD $HOLD · 표본 $out"

# **configmap 키는 `/` 를 못 담는다** — 두 스크립트의 `import "./lib/session.js"` 는 아래 볼륨 `items` 가 그 경로에 건다.
# 세션 파일(1만 개 1.2MB)은 configmap 상한 1MiB 를 넘어서 싣지 않고, initContainer 가 같은 파드의 emptyDir 에 만든다.
kubectl -n "$NS" create configmap k6-hpa \
  --from-file=open-rush.js=load/open-rush.js --from-file=prelogin.js=load/prelogin.js --from-file=session.js=load/lib/session.js \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$NS" delete job k6-hpa --ignore-not-found --wait >/dev/null
# 잡을 지워도 파드는 뒤따라 지워진다 — 남은 파드를 아래 `wait` 가 집으면 「not found」로 선다
kubectl -n "$NS" wait --for=delete pod -l job-name=k6-hpa --timeout=120s >/dev/null 2>&1 || true
kubectl -n "$NS" apply -f - >/dev/null <<YAML
apiVersion: batch/v1
kind: Job
metadata:
  name: k6-hpa
  namespace: $NS
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      initContainers:
        - name: prelogin
          image: $K6_IMAGE
          args: ["run", "-q", "/scripts/prelogin.js"]
          env:
            - { name: BASE_URL, value: "http://app:8080" }
            - { name: ACCOUNTS, value: "$ACCOUNTS" }
          resources: { limits: { memory: 1Gi } }
          volumeMounts:
            - { name: script, mountPath: /scripts }
            - { name: results, mountPath: /load/results }
      containers:
        # 본판은 신호 파일(go)을 기다린다 — 미리 로그인 1만만으로 HPA 가 6대까지 늘어서(52 첫 판), 3대로 돌아온 뒤 스크립트가 놓는다
        - name: k6
          image: $K6_IMAGE
          command: ["sh", "-c", "while [ ! -f /load/results/go ]; do sleep 2; done; exec k6 run /scripts/open-rush.js"]
          env:
            - { name: BASE_URL, value: "http://app:8080" }
            - { name: PERFORMANCE_ID, value: "$perf" }
            - { name: FIRST_SEAT_ID, value: "$first" }
            - { name: SEATS, value: "$seats" }
            - { name: ACCOUNTS, value: "$ACCOUNTS" }
            - { name: VUS, value: "$VUS" }
            - { name: HOLD, value: "$HOLD" }
            - { name: POLL_P95, value: "$POLL_P95" }
            - { name: HOLD_WON_P95, value: "$HOLD_WON_P95" }
          # k6 가 넘치면 노드가 아니라 k6 만 죽게(stack.md 「k6」). 이 heredoc 은 변수를 풀어서 백틱도 명령으로 돈다 — 안 쓴다
          resources: { limits: { memory: 2Gi } }
          volumeMounts:
            - { name: script, mountPath: /scripts }
            - { name: results, mountPath: /load/results }
      volumes:
        - name: script
          configMap:
            name: k6-hpa
            items:
              - { key: open-rush.js, path: open-rush.js }
              - { key: prelogin.js, path: prelogin.js }
              - { key: session.js, path: lib/session.js }
        - name: results
          emptyDir: {}
YAML

# 미리 로그인(5분 남짓)이 끝나 본판 컨테이너가 뜨면, HPA 가 최소 대수로 돌아올 때까지 기다렸다가 놓는다 —
# 로그인이 늘린 대수(줄이기 안정화 300초)에서 시작하면 「3 → N」을 못 잰다. 세션은 무활동 30분이라 그 안에 놓는다.
kubectl -n "$NS" wait --for=condition=ready pod -l job-name=k6-hpa --timeout=1500s >/dev/null
min=$(kubectl -n "$NS" get hpa app -o jsonpath='{.spec.minReplicas}')
# Ready 수만 보면 늘리는 중(새 파드가 아직 안 Ready)을 「최소」로 잘못 읽는다 — 목표 대수와 Ready 를 둘 다 본다.
for _ in $(seq 1 120); do
  [ "$(kubectl -n "$NS" get deployment app -o jsonpath='{.spec.replicas} {.status.readyReplicas}')" = "$min $min" ] && break
  sleep 10
done
now=$(kubectl -n "$NS" get deployment app -o jsonpath='{.spec.replicas} {.status.readyReplicas}')
# 안 돌아왔으면 놓지 않는다 — 「3 → N」을 재는 판이라 다른 대수에서 시작한 판은 결과가 아니다
[ "$now" = "$min $min" ] || { echo "HPA 가 20분 안에 최소 대수($min)로 안 돌아왔다: $now — 판을 놓지 않는다" >&2; exit 1; }
echo "== 대수 ${now% *}/${now#* } 에서 놓는다"
pod=$(kubectl -n "$NS" get pods -l job-name=k6-hpa -o jsonpath='{.items[0].metadata.name}')
# Git Bash 는 `/load/…` 인자를 윈도 경로로 바꾼다(stack.md) — 끈다
MSYS_NO_PATHCONV=1 kubectl -n "$NS" exec "$pod" -c k6 -- touch /load/results/go
echo "== 본판 시작 $(date -u +%s)"

# 5초마다: 시각 · HPA 현재 대수 · 평균 CPU(requests 대비 %) · 파드마다 이름·시작·Ready 시각 · 파드마다 CPU·메모리.
# 셋째·넷째가 「70% 를 넘은 시각 → 새 파드 Ready」와 대별 쏠림(49 흡수)을 가른다.
(
  while true; do
    echo "== $(date -u +%s) $(kubectl -n "$NS" get hpa app -o jsonpath='{.status.currentReplicas} {.status.desiredReplicas} {.status.currentMetrics[0].resource.current.averageUtilization}' 2>/dev/null)"
    pods=$(kubectl -n "$NS" get pods -l app=app -o jsonpath='{range .items[*]}pod {.metadata.name} {.status.startTime} {.status.conditions[?(@.type=="Ready")].status} {.status.conditions[?(@.type=="Ready")].lastTransitionTime} del={.metadata.deletionTimestamp}{"\n"}{end}' 2>/dev/null || true)
    printf '%s\n' "$pods"
    # Ready 가 된 파드마다 한 번 기동 줄을 건진다. 줄: 이름 · startTime · Ready 시각 · Started 시각(UTC 시:분:초) · JVM 이 센 초.
    # **`kubectl logs` 로 안 읽는다** — 부하 중 요청 로그가 kubelet 의 로그 파일을 몇 초 만에 돌려 써서 기동 줄이 없다.
    # 컨테이너 안 FILE 로그(`/app/logs/ticket.log`, 하루 단위로 돈다)에는 남는다. 클래스 이름은 `TicketApplicationKt` 다(53a)
    # 줄이는 중인 파드(`del=` 뒤에 시각)는 건너뛴다 — 종료 중인 컨테이너에는 `exec` 가 안 붙어 빈 칸만 남는다(`54` 첫 판)
    while read -r _ name start ready since del; do
      [ "$ready" = True ] && [ "$del" = del= ] || continue
      grep -q "^$name " "$out/started.log" 2>/dev/null && continue
      line=$(MSYS_NO_PATHCONV=1 kubectl -n "$NS" exec "$name" -c app -- grep -m1 "$STARTED" /app/logs/ticket.log 2>/dev/null || true)
      echo "$name $start $since ${line%% *} $(grep -o 'in [0-9.]* seconds (process running for [0-9.]*)' <<<"$line")" >> "$out/started.log"
    done <<<"$pods"
    # 이 서브셸도 `pipefail` 이다 — metrics-server 가 아직이면 `top` 이 실패해 표본 고리가 통째로 죽는다
    kubectl -n "$NS" top pods -l app=app --no-headers 2>/dev/null | sed 's/^/top /' || true
    sleep 5
  done
) > "$out/samples.log" &
sampler=$!
# 표본 고리는 판 끝에 이미 끝냈다(보충 앞) — 여기서 `kill` 이 실패해도 `set -e` 가 판정을 1 로 바꾸지 않게(`54`)
trap 'kill $sampler 2>/dev/null || true' EXIT

# 문턱을 넘으면 k6 가 0 이 아닌 코드로 끝나 Job 이 Failed 다 — 그것도 결과라 둘 다 기다린다. HOLD 660s + 램프·꼬리 > 12분.
for _ in $(seq 1 240); do
  done_=$(kubectl -n "$NS" get job k6-hpa -o jsonpath='{.status.succeeded}{.status.failed}')
  [ -n "$done_" ] && break
  sleep 5
done

kubectl -n "$NS" logs job/k6-hpa -c k6 > "$out/k6.log" || true
# 표본 고리가 못 건진 파드만 보충한다 — 줄을 못 건진 채 적힌 파드(끝 칸이 빈 것)와 판 막판에 뜬 파드.
# 표본 고리를 먼저 끝낸다 — 둘이 같이 `started.log` 에 쓰면 막판에 Ready 된 파드가 두 줄이 된다
kill "$sampler" 2>/dev/null || true
wait "$sampler" 2>/dev/null || true
for p in $(kubectl -n "$NS" get pods -l app=app -o jsonpath='{.items[*].metadata.name}'); do
  grep -q "^$p .*)$" "$out/started.log" 2>/dev/null && continue
  line=$(MSYS_NO_PATHCONV=1 kubectl -n "$NS" exec "$p" -c app -- grep -m1 "$STARTED" /app/logs/ticket.log 2>/dev/null || true)
  echo "$p - - ${line%% *} $(grep -o 'in [0-9.]* seconds (process running for [0-9.]*)' <<<"$line") (판 뒤)"
done >> "$out/started.log"

echo "== k6 요약"
grep -E "✓|✗|entered|seats_won|seats_taken|hold_won_ms|poll_latency_ms|wait_over_expected|http_req_failed|http_reqs" "$out/k6.log" || true
echo "== 대수 최대: $(awk '/^==/{print $3}' "$out/samples.log" | sort -n | tail -1) · Job: $(kubectl -n "$NS" get job k6-hpa -o jsonpath='{.status.conditions[0].type}') · 표본 $out"
cat "$out/started.log"

# 판의 판정(`54`). 예전에는 Job 이 Failed 여도 exit 0 이라 5xx 가 나도 같은 모양이었다
rc=0
[ "$(kubectl -n "$NS" get job k6-hpa -o jsonpath='{.status.succeeded}')" = 1 ] \
  || { echo "k6 Job 이 성공이 아니다 — 문턱(폴링 p95<$POLL_P95 · 이긴 선점 p95<$HOLD_WON_P95 · 5xx 0)을 넘었거나 시간 안에 안 끝났다" >&2; rc=1; }
# 파드마다 기동 줄을 한 번은 건져야 한다 — 빈 칸이 남으면 「생성 → Started」를 못 가른 판이다
blank=$(awk '{seen[$1]=1} /process running for/{ok[$1]=1} END{for (p in seen) if (!(p in ok)) print p}' "$out/started.log")
[ -z "$blank" ] || { echo "기동 줄을 못 건진 파드: $(echo "$blank" | tr '\n' ' ')" >&2; rc=1; }
exit $rc
