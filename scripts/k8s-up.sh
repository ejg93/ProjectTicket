#!/usr/bin/env bash
# 로컬 kind 클러스터에 앱과 의존 셋을 올린다(`36`). 다시 불러도 된다 — 클러스터가 있으면 이미지만 다시 굽고 적용한다.
# 이미지는 레지스트리를 안 거친다: 굽고 `kind load` 로 노드마다 넣는다(노드 셋이라 2~3분 걸린다 — 실패가 아니다).
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=ticket
IMAGE=ticket-app:local
WEB_IMAGE=ticket-web:local

bash scripts/docker-up.sh
kind get clusters 2>/dev/null | grep -qx "$CLUSTER" || kind create cluster --config k8s/kind.yaml
kubectl config use-context "kind-$CLUSTER" >/dev/null
# `k8s/kind.yaml` 의 포트 연결(`36a`)은 클러스터를 만들 때만 든다 — 옛 클러스터면 화면이 호스트 3300 에 안 열린다.
docker port "$CLUSTER-control-plane" 30300 >/dev/null 2>&1   || echo "== 이 클러스터는 k8s/kind.yaml 의 포트 연결 전에 만들어졌다 — 화면을 3300 에서 보려면 k8s-down.sh 뒤 다시 올린다"

docker build -t "$IMAGE" backend
# 화면의 `/api` 프록시 목적지는 빌드 때 굳는다(`stack.md`) — 앱 Service 이름을 빌드 인자로 준다.
docker build --build-arg BACKEND_ORIGIN=http://app:8080 -t "$WEB_IMAGE" frontend
kind load docker-image "$IMAGE" "$WEB_IMAGE" --name "$CLUSTER"

# HPA(`38`)가 읽는 CPU 지표. 없으면 HPA 가 `<unknown>` 이다.
kubectl apply -f k8s/metrics-server.yaml >/dev/null

existed=$(kubectl -n ticket get deployment app -o name 2>/dev/null || true)
# 차트(`37`)가 다섯을 들고 준비될 때까지 기다린다. 값이 그대로면 아무것도 안 바뀐다.
helm upgrade --install ticket k8s/chart -n ticket --create-namespace --wait --timeout 5m
# 같은 태그로 다시 구웠으면 파드가 옛 이미지를 쥐고 있다 — 새로 띄운다. 처음이면 막 뜨는 파드를 죽이지 않게 건너뛴다.
if [ -n "$existed" ]; then
  kubectl -n ticket rollout restart deployment/app deployment/web >/dev/null
  kubectl -n ticket rollout status deployment/app --timeout=300s
  kubectl -n ticket rollout status deployment/web --timeout=300s
fi
