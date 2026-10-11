#!/usr/bin/env bash
# 차트 렌더 결과를 `k8s/chart/snapshot.yaml` 로 굳힌다(`37`). 템플릿이나 값을 고쳤으면 이것을 돌리고 같이 커밋한다 —
# `verify.sh` tools 레인과 CI `backend` 잡이 렌더와 스냅샷을 비교해 다르면 선다. `K8sManifestTest` 는 이 파일을 읽는다.
set -euo pipefail
cd "$(dirname "$0")/.."
helm template ticket k8s/chart -n ticket > k8s/chart/snapshot.yaml
echo "k8s/chart/snapshot.yaml 을 다시 썼다 — 커밋한다"
