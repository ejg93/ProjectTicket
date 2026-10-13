# 부하 시험 (31)

오픈 순간에 사람이 몰리는 모양을 흉내 내서 **어디가 먼저 막히나**를 본다. 결과는 초록·빨강이 아니라 숫자다 —
`doc/notes/load-*.md` 에 기계 사양과 같이 적고 `D13`(32)이 그 숫자로 목표를 정한다.

## 무엇을 재나

| 이름 | 무엇 | 어디서 |
|---|---|---|
| `http_req_duration` | 전체 응답 시간 | k6 기본 |
| `http_req_failed` | **5xx 와 연결 오류만**(`38`). 409·429 는 정상 흐름이라 안 센다 — 문턱 `rate==0` | k6 기본 + 응답 콜백 |
| `hold_latency_ms` | 선점 요청 하나 | 스크립트 |
| `admission_wait_ms` | 줄에 서서 토큰을 받기까지 | 스크립트 |
| `seats_won` · `seats_taken` | 좌석을 잡았나 남이 먼저 잡았나 | 스크립트 |
| `gate_rejected` | 관문이 막은 수(429) | 스크립트 |

앱 쪽 지표는 Grafana 가 같은 시간대를 보여 준다(30) — `seat.hold.attempts`·`queue.length`·`queue.wait_seconds`.

## 돌리는 법

```bash
# 1. 인스턴스 셋 + 문 + 데모 데이터 — **빈 DB·Redis 로**(load/compose.fresh.yml). 데모는 프로필로 켠다.
#    재사용 DB 는 데모 회차가 첫 기동 + 14일에 닫혀서 줄을 못 선다(stack.md). 앱 이미지는 지금 코드로 다시 굽는다.
export SPRING_PROFILES_ACTIVE=local
docker compose -f docker-compose.yml -f load/compose.fresh.yml build app
docker compose -f docker-compose.yml -f load/compose.fresh.yml up -d --scale app=3

# 2. 계정 1만과 좌석 번호를 준비한다. 마지막 select 가 회차 id·첫 좌석 id·좌석 수를 준다
docker exec -i ticket-db psql -U ticket -d ticket < load/prepare.sql

# 3. k6 를 같은 망에서 띄운다(nginx 를 이름으로 부른다). **판을 박고, 폴더를 마운트한다** —
#    표준입력(`run -`)으로 넘기면 `import "./lib/…"` 가 안 된다(35b). Git Bash 면 MSYS_NO_PATHCONV=1 과 $(pwd -W)
MSYS_NO_PATHCONV=1 docker run --rm --network projectticket_default -v "$(pwd -W)/load:/load" \
  -e BASE_URL=http://nginx:80 -e PERFORMANCE_ID=1 -e FIRST_SEAT_ID=1 -e SEATS=1000 \
  -e VUS=100 -e RAMP=20s -e HOLD=40s \
  grafana/k6:2.2.0 run /load/seat-rush.js
```

**`PRELOGIN=1`** 이면 `setup()` 이 계정 `ACCOUNTS` 개를 미리 로그인하고 반복은 로그인을 건너뛴다 — 순수 예매 경로(35b).
요약의 `http_req_duration{phase:run}` 이 측정 요청만의 값이다(미리 로그인은 `phase:prelogin`).
부하 계정의 해시는 bcrypt **비용 4** 다(`prepare.sql`) — 2차까지(비용 10)와 로그인이 섞인 숫자를 비교할 때 그 차이를 같이 적는다.

`VUS`·`RAMP`·`HOLD` 로 크기를 바꾼다. **기계가 감당하는 만큼만 올린다** — k6 와 앱 셋과 DB 가 한 노트북에 있으면
숫자가 서버가 아니라 그 노트북을 재는 것이 된다. 그 사실도 리포트에 적는다.

## 다시 돌릴 때

좌석은 한 번 잡히면 다음 회차 실행에서 전부 `409` 다. 같은 조건으로 다시 재려면 예매를 지운다 —
**`performance_seat` 를 먼저 풀어야 한다.** `performance_seat.reservation_id` 가 `reservation` 을 가리켜서
예매를 먼저 지우면 외래키에 걸린다(`ticket` → `reservation_seat` 도 같은 이유로 순서가 있다).

```bash
docker exec -i ticket-db psql -U ticket -d ticket -v ON_ERROR_STOP=1 -c "
update performance_seat set status='available', held_until=null, reservation_id=null where performance_id=1;
delete from ticket where reservation_seat_id in (select reservation_seat_id from reservation_seat where reservation_id in (select reservation_id from reservation where performance_id=1));
delete from reservation_seat where reservation_id in (select reservation_id from reservation where performance_id=1);
delete from payment where reservation_id in (select reservation_id from reservation where performance_id=1);
delete from notification;
delete from reservation where performance_id=1;
delete from idempotency_key;"
```

**되돌린 뒤 Redis 의 좌석 판은 안 맞는다**(`D20` — 판은 서비스가 올린다). 줄·하트비트·입장 토큰과 같이 지운다 —
토큰(`admit:*`)이 남으면 다음 판의 계정이 줄을 안 서고 바로 입장한다:

```bash
docker exec ticket-redis redis-cli del seat:ver:1 seat:log:1 queue:1 queue:1:seen queue:1:active
docker exec ticket-redis sh -c "redis-cli --scan --pattern 'admit:*' | xargs -r redis-cli del"
```

빈 DB 를 처음부터 다시 만들려면 `docker compose -f docker-compose.yml -f load/compose.fresh.yml down` 뒤
`docker volume rm projectticket_db-load-data projectticket_redis-load-data`. **`down -v` 는 원래 볼륨까지 지운다.**
끝나면 이 덧파일 없이 `docker compose up -d` — 원래 볼륨으로 돌아온다.

## 대기열 1만(35c)

줄에 선 사람 1만을 VU 500 이 계정 20 개씩 들고 돈다(`queue-surge.js`). 세션은 **먼저 한 판으로** 만든다 —
`setup()` 반환값은 VU 마다 복사돼 1만 × 500 이 Docker VM 메모리를 넘긴다. 세션은 무활동 30분에 끝나니 바로 이어서 돌린다.

```bash
# 1. 세션 1만 → load/results/sessions.json (무시 목록이라 새로 받은 저장소에는 폴더가 없다). 3분쯤
mkdir -p load/results
MSYS_NO_PATHCONV=1 docker run --rm --memory=2g --network projectticket_default -v "$(pwd -W)/load:/load" \
  -e ACCOUNTS=10000 grafana/k6:2.2.0 run /load/prelogin.js

# 2. 줄을 비운다 — 위 「다시 돌릴 때」의 Redis 두 줄

# 3. 줄에 선다. --memory 는 k6 가 넘치면 VM 이 아니라 k6 만 죽게 하려는 것이다
MSYS_NO_PATHCONV=1 docker run --rm --memory=2g --network projectticket_default -v "$(pwd -W)/load:/load" \
  -e ACCOUNTS=10000 -e VUS=500 -e RAMP=60s -e HOLD=600s grafana/k6:2.2.0 run /load/queue-surge.js
```

요약에서 읽을 것: `entered`(= 계정 수) · `left` + `release_failed` + `dropped`(= `entered` 라야 한다) · `poll_round_ms`(바퀴 간격, 2초 언저리라야 한다) · `wait_over_expected`(대기 ÷ (순번 ÷ 20) — `D13` 은 ≤ 2) ·
`poll_latency_ms` · `http_req_failed`. 대수별 입장은 아래 「대수가 실제로 갈렸나」처럼 앱마다 `queue_admitted_total` 을 읽는다 —
**Prometheus 는 `app:8080` 하나를 긁어 셋 중 아무 대나 본다.**

## 좌석 폴링(35d)

공개 GET 을 계단으로 치는 `seat-poll.js` 와, 그동안 판을 바꾸는 `seat-rush.js`(`ROTATE=1`)를 같이 돌린다.
**`ROTATE=1` 은 계정과 좌석을 반복마다 돌린다** — 안 돌리면 계정 수만큼 잡고 나서 판이 안 바뀐다.

```bash
# 1. 쓰는 쪽 — 미리 로그인(1천, 20초)이 끝난 뒤 2 를 띄운다
MSYS_NO_PATHCONV=1 docker run --rm --memory=1g --network projectticket_default -v "$(pwd -W)/load:/load" \
  -e VUS=10 -e RAMP=10s -e HOLD=230s -e PRELOGIN=1 -e ROTATE=1 -e ACCOUNTS=1000 grafana/k6:2.2.0 run /load/seat-rush.js

# 2. 폴링 계단(200초)
MSYS_NO_PATHCONV=1 docker run --rm --memory=2g --network projectticket_default -v "$(pwd -W)/load:/load" \
  grafana/k6:2.2.0 run /load/seat-poll.js

# 3. 그동안 5초마다 판과 앱마다의 스냅샷 생성 수를 적는다 — 계단 구간(0~60·70~130·140~200초)의 앞뒤 차가 결과다
while :; do echo "$(date -u +%s) $(docker exec ticket-redis redis-cli get seat:ver:1) $(for i in 1 2 3; do \
  docker exec projectticket-app-$i curl -s localhost:8080/actuator/prometheus | awk '/^seat_snapshot_built_total/{printf "%s ", $2}'; done)"; sleep 5; done
```

판 변경 지표는 없다 — `seat:ver` 의 차로 센다. 스냅샷 생성을 Prometheus 로 읽지 않는 이유는 아래 절과 같다(`app:8080` 하나를 긁는다).

## 대수가 실제로 갈렸나 본다

부하를 돌린 뒤 **인스턴스마다** 센다. 한 대에 몰려 있으면 문이 안 나눈 것이다(35).

```bash
for i in 1 2 3; do docker exec projectticket-app-$i \
  curl -s http://localhost:8080/actuator/prometheus | grep ^seat_hold_latency_seconds_count; done
```
