# ProjectTicket

공연 예매 시스템. 기획사가 회차를 열고, 관객이 대기열을 지나 좌석을 선점·결제한다.
목적은 티켓을 파는 것이 아니라 **오픈 순간의 동시성·대기열·정합성·비동기 분리**를 설계하고 재는 것이다.

만드는 대상과 근거는 `PLAN.md`, 어디까지 했는지는 `PROGRESS.md`, 왜 이 스택인지는 `doc/adr/0001-stack.md` 에 있다.

## 구성

| 경로 | 무엇이 들어 있나 |
|---|---|
| `backend/` | Kotlin + Spring Boot 서버. 좌석 선점·예매·결제·대기열 (청크 1 부터) |
| `frontend/` | Next.js 앱. 좌석도·예매·대기열 화면 (청크 39 부터) |
| `docker-compose.yml` | PostgreSQL·Redis. Kafka·Prometheus 는 해당 청크에서 더한다 |
| `doc/` | 기준 문서·ADR·측정 기록 |

## DB 띄우기

```bash
cp .env.example .env
docker compose up -d
docker compose ps        # db·redis 가 healthy
```

내리기는 `docker compose down`, 데이터까지 지우려면 `down -v`.

## 백엔드 띄우기

청크 1 이 닫힌 뒤부터. 자세한 건 `backend/README.md`.

```bash
cd backend
JAVA_HOME="C:/Program Files/Java/jdk-25" ./gradlew bootRun
curl localhost:8080/api/health
```

## 요구 사항

- Docker Desktop
- JDK 25
- Node.js 22 이상 (프론트 청크부터)

## 라이선스

Apache-2.0.
