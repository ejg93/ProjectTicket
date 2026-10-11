#!/usr/bin/env bash
# 로컬 kind 클러스터를 지운다(`36`). 볼륨(PVC)도 같이 사라진다 — 데모 시드는 다음 기동에 다시 심긴다.
set -euo pipefail
kind delete cluster --name ticket
