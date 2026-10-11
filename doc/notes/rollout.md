# 무중단 배포 — 롤링 재시작 동안의 5xx (38)

2026-10-11. 스크립트는 `scripts/k8s-rollout-test.sh`, 부하는 `load/seat-rush.js`(클러스터 안 k6 Job).

**숫자는 이 기계의 것이다.** kind 노드 셋·앱·Postgres·Redis·Kafka·k6 가 전부 같은 노트북의 Docker 안에 있다.

| 무엇 | 값 |
|---|---|
| 기계 | Intel Core Ultra 7 155H (16코어 22스레드), Windows 11 |
| Docker | 22 vCPU / 7.75GB 할당 |
| 구성 | kind 0.33.0 (control-plane 1 + worker 2), 차트 `k8s/chart` — 앱 Deployment(HPA 3~6, CPU 70%) + Postgres·Redis·Kafka 한 대씩 |
| 롤링 | `maxSurge 1` · `maxUnavailable 0` · readiness `/actuator/health/readiness` · graceful 20초 · grace 30초 |
| 부하 | VU 100, 램프 20s / 유지 150s / 내림 10s. 계정 300, 좌석 1,000석. 판 2·3 은 부하 20초 뒤 `rollout restart` |
| 실패의 정의 | **5xx 와 연결 오류만** — 409·429 는 정상 흐름이라 응답 콜백이 성공으로 센다(`load/README.md`) |

## 잰 것

| 판 | 롤링 | preStop | 실패 / 요청 | HTTP p95 | 선점 p95 | 앱 대수 최대 |
|---|---|---|---|---|---|---|
| 1 | 없음 | 5초 | **0** / 58,212 | 341ms | 98ms | 6 |
| 2 | 있음 | 없음 | **2** / 57,956 | 337ms | 133ms | 7 |
| 3 | 있음 | 5초 | **0** / 58,278 | 327ms | 130ms | 7 |

판 2 의 실패 둘은 `dial: i/o timeout` 이다 — 이미 내려간 파드 IP 로 연결을 걸었다.
readiness 가 빠지고 graceful 종료가 있어도 **엔드포인트 제거가 kube-proxy 에 퍼지기 전에 SIGTERM 이 오면** 그 사이 연결이 닫힌 대로 간다.
preStop 5초가 SIGTERM 을 그만큼 미뤄 판 3 에서 0 이 됐다.

앱 대수 최대 7 은 HPA 의 6 위에 롤링의 `maxSurge 1` 이 얹힌 값이다. 판 1 에서도 6 까지 늘었다 — 로그인의 bcrypt 가 CPU 를 쓴다.

## 이 숫자가 말하지 않는 것

- **판마다 한 번씩 돌렸다.** 판 2 의 2 건은 5.8만 요청 중이라 판 3 의 0 이 우연일 수 있다. 5초가 넉넉한지는 이 표만으로 못 말한다
- p95 는 세 판이 같은 폭 안이다 — 롤링이 응답 시간을 눈에 띄게 늘리지 않았다는 것까지만 읽는다
- kube-proxy 의 전파 시간은 노드 수·엔드포인트 수에 따라 달라진다. 노드 셋짜리 kind 의 값이다
