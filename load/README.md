# 부하 시험 (31)

오픈 순간에 사람이 몰리는 모양을 흉내 내서 **어디가 먼저 막히나**를 본다. 결과는 초록·빨강이 아니라 숫자다 —
`doc/notes/load-*.md` 에 기계 사양과 같이 적고 `D13`(32)이 그 숫자로 목표를 정한다.

## 무엇을 재나

| 이름 | 무엇 | 어디서 |
|---|---|---|
| `http_req_duration` | 전체 응답 시간 | k6 기본 |
| `hold_latency_ms` | 선점 요청 하나 | 스크립트 |
| `admission_wait_ms` | 줄에 서서 토큰을 받기까지 | 스크립트 |
| `seats_won` · `seats_taken` | 좌석을 잡았나 남이 먼저 잡았나 | 스크립트 |
| `gate_rejected` | 관문이 막은 수(429) | 스크립트 |

앱 쪽 지표는 Grafana 가 같은 시간대를 보여 준다(30) — `seat.hold.attempts`·`queue.length`·`queue.wait_seconds`.

## 돌리는 법

```bash
# 1. 인스턴스 셋 + 문 + 데모 데이터. 데모는 프로필로 켠다(SPRING_PROFILES_ACTIVE=local)
SPRING_PROFILES_ACTIVE=local docker compose up -d --scale app=3

# 2. 계정과 좌석 번호를 준비한다. 마지막 select 가 회차 id·첫 좌석 id·좌석 수를 준다
docker exec -i ticket-db psql -U ticket -d ticket < load/prepare.sql

# 3. k6 를 같은 망에서 띄운다(nginx 를 이름으로 부른다)
docker run --rm -i --network projectticket_default \
  -e BASE_URL=http://nginx:80 -e PERFORMANCE_ID=1 -e FIRST_SEAT_ID=1 -e SEATS=1000 \
  -e VUS=100 -e RAMP=20s -e HOLD=40s \
  grafana/k6 run - < load/seat-rush.js
```

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

**되돌린 뒤 Redis 의 좌석 판은 안 맞는다**(`D20` — 판은 서비스가 올린다). 줄과 같이 지운다:

```bash
docker exec ticket-redis redis-cli del seat:ver:1 seat:log:1 queue:1
```

## 대수가 실제로 갈렸나 본다

부하를 돌린 뒤 **인스턴스마다** 센다. 한 대에 몰려 있으면 문이 안 나눈 것이다(35).

```bash
for i in 1 2 3; do docker exec projectticket-app-$i \
  curl -s http://localhost:8080/actuator/prometheus | grep ^seat_hold_latency_seconds_count; done
```
