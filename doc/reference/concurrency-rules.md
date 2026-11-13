# 동시성·트랜잭션 규약

**좌석을 건드리는 모든 청크에 걸린다.** 이 저장소의 핵심이 「오픈 순간에 같은 좌석을 둘이 못 잡는다」이고, 그 답이 여기 있다.

## 격리 수준 — Read Committed 그대로

Postgres 기본값을 안 바꾼다. **조건부 UPDATE 가 Read Committed 에서 정확히 「승자 하나」를 보장하기 때문**이고, 그 근거는 아래다.

Postgres 문서 §13.2.1 이 정한 동작: `UPDATE` 가 고른 행이 **다른 트랜잭션에 의해 이미 갱신 중이면 그 트랜잭션이 끝날 때까지 기다린다.**
그쪽이 커밋하면 **갱신된 행으로 `WHERE` 를 다시 평가**하고, 여전히 맞으면 갱신하고 아니면 그 행을 건너뛴다(EvalPlanQual).

```
T1: update performance_seat set status='held' … where id=7 and status='available'   -- 잠금 획득, 갱신
T2: update performance_seat set status='held' … where id=7 and status='available'   -- 대기
T1: commit
T2: id=7 의 새 버전으로 where 재평가 → status='held' 라 안 맞음 → 0행
```

T2 는 오류가 아니라 **0행**을 받는다. 그래서 갱신 행 수가 판정이다. `SERIALIZABLE` 이 필요 없고, 재시도 루프도 필요 없다 —
0행은 「남이 이겼다」는 확정 답이지 「다시 해 보라」가 아니다.

**`SELECT` 로 먼저 확인하는 코드를 쓰지 않는다.** 읽고 판단하고 쓰면 그 사이에 남이 끼어들고, Read Committed 는 그것을 안 막는다.

## 좌석 선점 — 조건부 UPDATE

```sql
update performance_seat
   set status = 'held', held_until = :heldUntil, reservation_id = :reservationId
 where performance_seat_id = any(:seatIds)
   and status = 'available'
   and performance_id = :performanceId
   and exists (select 1 from performance p
                where p.performance_id = :performanceId and p.status = 'open')
```

| 규칙 | 왜 |
|---|---|
| 갱신 행 수 = 요청 좌석 수가 아니면 **통째로 롤백** | 4석 중 3석만 잡히면 그 3석도 안 잡는다. 「가능한 것만」은 없다(ADR 0003) — 있으면 실패 응답에 「어느 좌석이 됐나」가 붙고 화면·정산이 부분을 다뤄야 한다 |
| 회차 행을 `for share` 로 먼저 잠근다. 판매 시작(`sales_open_at <= now()`)도 거기서 본다 | 닫힌 회차의 좌석도 `available` 로 남아 있다(`V6` 는 회차 상태를 모른다). 위 `exists` 는 문장 스냅샷이라 **커밋 전 취소·종료를 `open` 으로 읽고**, 취소의 `for update` 는 커밋 전 예매를 못 본다 — 둘 다 커밋하면 취소된 회차에 `held` 가 남는다(`I13-1`). `for share` 는 취소·종료의 `update performance`(FOR NO KEY UPDATE)와 부딪쳐 순서를 세운다 — `for key share` 는 안 부딪친다. `exists` 는 남긴다 |
| `performance_id` 를 같이 건다 | 다른 회차의 좌석 id 를 섞어 보내는 요청이 그 회차의 좌석을 못 잡는다 |
| 상태는 열거형 `code` 를 바인딩한다 — 리터럴로 두는 예외(부분 인덱스 짝·계층)는 `D14` 「SQL」 이 든다 | `D14` 「SQL」 |

**좌석 상태를 바꾸는 `update performance_seat` 는 전부 `where` 에 좌석 `status` 조건을 단다** — 선점만이 아니라 확정·해제·취소·스윕도다.
`SqlTextTest` 가 `src/main/kotlin` 의 원시 문자열을 훑어 막는다(`G7b`). 지금 일곱이다.

### 여러 행을 잠글 때 — id 오름차순으로 먼저 잠근다

한 문장 `UPDATE … WHERE id = ANY(…)` 는 **행을 잠그는 순서를 보장하지 않는다.** 두 요청이 겹치는 좌석을 다른 순서로 잠그면
Postgres 가 교착을 감지해 하나를 죽인다(`40P01`). 죽은 쪽은 재시도하면 되지만, 그 요청은 이미 예매 행을 만든 뒤라 롤백 범위가 크다.

그래서 **잠금을 먼저, 정해진 순서로** 건다:

```sql
select performance_seat_id
  from performance_seat
 where performance_seat_id = any(:seatIds)
 order by performance_seat_id
   for update
```

그다음 위 조건부 UPDATE 를 던진다. 같은 트랜잭션이라 잠근 행을 그대로 갱신하고, `status = 'available'` 재평가는 위와 같다.
두 문장이 되지만 좌석은 최대 4석(ADR 0003)이라 비용이 없다.

**교착이 그래도 나면 1회 재시도한다.** `40P01` 은 「다시 하면 될 수 있다」의 뜻이고 0행과 다르다(아래 「재시도」).

**만료 스윕은 기다리지 않는다**(`I17-8`). `HoldSweeper` 는 만료된 예매를 `order by reservation_id limit 500 for update skip locked` 로 먼저 잡고 그것만 옮긴다.
회차 취소·종료가 회차 행을 잡은 뒤 예매를 id 순으로 잠그는 동안 스윕이 임의 순서로 기다리면 `40P01` 이 났다 — 안 기다리는 쪽은 교착 고리에 못 든다.
남이 쥔 예매는 그쪽이 끝내고, 500 을 넘는 나머지는 다음 회(30초)다. 종료 ↔ 취소는 같은 회차면 회차 행에서 줄을 서고 다른 회차면 예매가 안 겹쳐 교착이 없다.

### 선점의 문장 순서 — check 제약이 정했다

`performance_seat_held_fields_check`(`V6`)가 `held` 행에 `reservation_id` 를 요구한다. 그래서 순서는 이렇다:

```
0. 회차 행 select … for share (open 이고 판매 시작이 지났나) → 취소·종료와 순서가 선다(I13-1)
1. reservation 행 삽입 (held, held_until)        → reservation_id 를 얻는다
2. 좌석 id 오름차순 select … for update
3. 조건부 update (위 문장)
4. 갱신 행 수 ≠ 요청 좌석 수 → 롤백 (1의 행도 사라진다)
5. reservation_seat 에 좌석·가격 기록
6. 커밋
```

**전부 한 트랜잭션이다.** 4 에서 롤백되면 진 쪽의 예매 행이 안 남는다. 1 을 트랜잭션 밖에서 하면 고아 예매가 남는다.

## 두 표의 역할 — 포인터와 기록

| 표 | 무엇 | 언제 바뀌나 |
|---|---|---|
| `performance_seat.reservation_id` | **지금 이 좌석을 쥔 예매.** 포인터 | 선점·해제·확정마다 |
| `reservation_seat` | **이 예매가 잡은 좌석과 그때 가격.** 기록 | 선점 때 한 번. 그 뒤 안 고친다 |

포인터는 좌석이 풀리면 null 이 되고, 기록은 남는다. 만료된 예매가 무엇을 잡았었는지, 확정된 예매의 좌석별 가격이 얼마였는지는 기록이 답한다.
**같은 것을 두 벌 드는 것이 아니다** — 하나는 현재고 하나는 과거다.

**불변식**: 예매가 `held`·`paying`·`reserved` 면 그 예매의 `reservation_seat` 좌석 집합 = `performance_seat` 에서 `reservation_id` 가 그 예매인 집합.
`ReservationSeatConsistencyTest`(13)가 매 전이 뒤에 대조한다. 트리거로 안 내리는 이유는 두 표를 잇는 조건이라 행 트리거가 볼 자리가 없어서다.
**확정은 앱에서 수를 맞춘다**(`I17-8`) — `confirm` 이 `reserved` 로 바꾼 좌석 수가 `reservation_seat` 수와 다르면 던져 결과 반영 전체를 롤백한다.
예매는 `paying` 으로 남아 만료되고, 대사가 그 승인을 `late` → 환불로 잇는다(`I17-1`).

**불변식**: 좌석은 예매를 바꿔 가리키지 않는다 — 포인터는 null→A·A→null 만 간다. 트리거 `performance_seat_no_handover`(`V24`, `51`)가 A→B 를 막는다. 초과 판매의 셋째 겹이다(앞의 둘은 조건부 UPDATE 의 `status` 조건과 갱신 행 수 대조).

**불변식**: 회차 좌석의 가격은 오픈 때 한 번 박제되고 안 바뀐다 — 트리거 `performance_seat_price_frozen`(`V32`, `I15-1`). 기록(`reservation_seat.price`)과 합계(`reservation.total_amount`)가 이 값을 베낀다.

## 합계 불변식

`reservation.total_amount = Σ reservation_seat.price`. 합계는 원본과 어긋날 수 있는 유일한 종류의 값이다(`D14` 「불변식」).

**지연 제약 트리거로 든다** — `create constraint trigger … deferrable initially deferred`. 커밋 시점에 합계와 좌석 가격의 합을 대조한다.
행 트리거를 즉시로 걸면 예매 행이 먼저 들어오는 순간(위 1 단계)에 좌석이 아직 없어서 0 과 대조하게 된다.

## 멱등키 — 클라이언트가 만든다

같은 요청이 두 번 도착해도 결과가 하나이게 만든다. 네트워크가 끊겨 응답을 못 받은 클라이언트가 재시도하고,
nginx(33)도 시간 초과에 재시도한다. **선점이 두 번 되면 같은 사람이 8석을 쥔다.**

| 항목 | 값 |
|---|---|
| 만드는 쪽 | 클라이언트 |
| 형식 | UUIDv4. 아니면 400 `validation-failed`(`IdempotencyKeys`) |
| 전달 | `Idempotency-Key` 요청 헤더 |
| 범위 | 계정별로 유일. 남의 키와 겹쳐도 상관없다 |
| 보관 | 24시간. **`IdempotencyCleaner` 가 한 시간마다 지운다**(`13b`) — 하루 지나 같은 키로 오는 것은 재시도가 아니라 다른 요청이다 |

| 어디에 | 요구 |
|---|---|
| `POST /api/performances/{id}/reservations`(선점, 13) | **필수.** 없으면 400 `validation-failed` |
| `POST /api/reservations/{id}/payments`(결제, 16) | **필수.** 키 선점이 PG 호출 **뒤**(③)라 앞 요청이 PG 를 기다리는 동안 온 재전송은 `idempotency-in-progress` 가 아니라 ① 의 0행 → 409 `invalid-transition`(from=`paying`) 이다 |
| `POST /api/reservations/{id}/cancel`(취소, 17) | **안 받는다** — 이미 있는 자원의 상태를 옮기는 것이라 조건부 UPDATE(`reserved → cancelled`)가 둘째 요청을 0행으로 끝내고, 0행은 409 `invalid-transition` 이다 |
| 그 밖 | 안 받는다 |

돈이나 좌석이 움직이는 `POST` 에만 건다. 전부에 걸면 클라이언트가 의미 없는 키를 만든다.

### 서버가 하는 일

```
키가 처음이다         → 선점하고 처리한다. 끝나면 응답 본문을 저장한다
같은 키가 또 왔다     → 저장된 본문을 그대로 돌려준다. 상태 코드는 안 저장한다 — 성공만 저장하고 입구마다 성공 코드가 하나라 입구가 안다
같은 키가 처리 중이다 → 앞 요청이 끝날 때까지 기다린다. lock_timeout(2초)을 넘기면 409 `idempotency-in-progress`
                        2초는 키를 잡는 insert 에만 건다 — 잡은 뒤 기본값으로 되돌린다. 안 되돌리면 같은 트랜잭션의 좌석 잠금까지 2초에 끊긴다(`I11-1`)
같은 키인데 본문이 다르다 → 422 `idempotency-key-reused`
```

**DB 쪽 시간 상한이 모든 앱 연결에 걸린다**(`I17-6`). 넘으면 503 `service-busy` + `Retry-After` 다 — 기다리는 요청이 연결을 쥐고 풀 10 을 채우지 않게 한다.

| 상한 | 값 | 거는 자리 |
|---|---|---|
| `lock_timeout` | 3초 | 드라이버 속성 `options`(시작 옵션). 키 insert 의 2초는 그 위에 `set local` 로 덮고 `= default` 로 3초에 돌아온다 |
| `statement_timeout` | 10초 | 같은 `options` |
| `idle_in_transaction_session_timeout` | 30초 | 같은 `options`. 릴레이 한 회 최악(예산 5초 + 한 건 `max.block.ms` 5초 + `delivery.timeout.ms` 15초 = 25초)이 이 안에 든다 — 고른 뒤 끝 UPDATE 까지 문장이 없다(`SchedulingConfigTest`) |
| 풀 대기(Hikari `connection-timeout`) | 3초 | `spring.datasource.hikari.connection-timeout` |

`connection-init-sql` 의 `set` 으로 걸지 않는다 — 세션 값이라 `= default` 가 무제한으로 되돌린다. `alter role ticket_app set` 도 안 된다 — `set role` 로는 안 실린다(`stack.md`).
선점의 교착 재시도에 `55P03` 을 안 넣는다 — 기다림이 두 배가 된다.

본문 비교는 요청 본문의 해시로 한다. 본문 전체를 보관하지 않는다.

**키 선점·처리·응답 저장을 한 트랜잭션에 둔다.** 갈라 두면 「예매는 커밋됐는데 키 기록은 진행중」인 구간이 생기고,
그 구간에서 서버가 죽으면 재전송이 영영 409 를 받는다. 한 트랜잭션이면 어디서 죽든 전부 롤백이라 반쪽 상태가 없다.

## 스윕과 확정의 경합 — 상태로 푼다

`D3` 의 `paying` 이 답이다. 스윕은 `held` 를 `held_until` 로, `paying` 을 `paying_until` 로 훑는다 — 결제가 시작되면 예매가 `paying` 이라 선점 만료 문장에 안 걸리고, 결제 타임아웃(3분)만 걸린다. 둘은 같은 스케줄러의 한 문장이다(14·16).

```sql
-- 스윕 (14). 둘 다 조건부라 두 번 돌아도 둘째는 0행이다
update reservation set status = 'expired', expired_at = now(), paying_until = null, cancelled_by = 'expired'
 where status = 'held' and held_until < now()
    or status = 'paying' and paying_until < now()
returning reservation_id;

update performance_seat set status = 'available', held_until = null, reservation_id = null
 where status = 'held' and reservation_id in (:첫 문장이 돌려준 id);
```

좌석은 **만료된 예매의 id 로 고른다**(`HoldSweeper`). `performance_seat.held_until < now()` 로 고르면 `paying` 예매의 좌석(아직 `held`, 시각은 지남)까지 풀린다.

**결제 승인도 조건부다** — `where status = 'paying' and paying_until >= now()`. 0행이면 승인을 받았어도 좌석을 못 주는 것이고,
그때는 감사에 `payment.late` 를 남기고(16) **되돌리는 것은 환불 행이다**(사유 `payment_late`, 17b, `D6` 「사유별」) — PG 취소를 트랜잭션 안에서 부르지 않으려는 것이고, 되돌리는 통로가 둘이면 이중 반환이다.

## 스케줄러 — 인스턴스 여럿에서 하나만

스윕·타임아웃·회차 종료는 전부 멱등이라 두 대가 같이 돌아도 결과는 같다. 그래도 **Redis 락으로 겹치지 않게 돌린다**(33, ADR 0003) —
같은 행을 두 번 스캔하는 비용이 있고, 로그가 두 벌 남으면 「몇 개를 만료시켰나」를 세는 사람이 헷갈린다.
락을 못 잡으면 그 회차는 건너뛴다. 기다리지 않는다. **락은 겹침만 막고 횟수는 안 막는다** — 일이 끝나면 놓아서 대마다 제 주기로 돈다.
횟수가 뜻을 갖는 입장은 속도를 락이 아니라 입장 스크립트의 예산 창(`queue:{id}:admit_window`)이 지킨다(`35f`, `D12`).
**Redis 가 죽으면 락 없이 돈다**(`I17-5`) — 던지면 DB 만 쓰는 선점 만료·회차 종료가 Redis 장애 내내 선다. **알림만 건너뛴다**(`OnOutage.SKIP`, 마무리 35차 사용자) — 겹치면 같은 메일이 대수만큼 나가서, Redis 가 돌아온 뒤 보낸다. 환불은 겹쳐도 `on conflict`·조건부 `complete`·환불 id 키로 한 번만 닫혀 락 없이 돈다.

| 락을 지나나 | 스케줄러 | 왜 |
|---|---|---|
| 지난다 | `hold-sweeper`·`performance-closer`·`admission-scheduler`·`queue-sweeper` | 위 이유 — 겹치면 스캔과 로그가 두 벌이다 |
| 지난다 | `notification-sweeper` | 겹치면 결과가 달라진다(`I17-5`) — `skip locked` 는 고르기와 함께 풀려 대수만큼 같은 메일이 나갔다 |
| 지난다 | `payment-reconciler` | 절약이다 — 겹치면 같은 시도를 PG 에 대수만큼 묻고 `settle` 이 겹친다. 승자는 `payment_approved_idx` 가 하나로 만든다(`I17-1`). 진 쪽은 `on conflict … do nothing` 의 0행으로 받아 기존 승인 행을 읽는다 — 원 요청은 그 행으로 201 이다(`I17-11`) |
| 지난다 | `account-purge` | 절약이다 — 두 문장이 조건부라 결과는 같지만 파드가 뜰 때마다 모든 파드가 같은 행을 잠그고 훑었다. 첫 회는 기동 10분 뒤(`I17-9`) |
| 지난다 | `refund-sweeper` | 절약이다 — 늦은 승인 환불 행을 두 대가 같이 만들면 한쪽 회가 끊겼고(지금은 `on conflict`), 같은 행을 두 대가 PG 로 보냈다(`I17-5`) |
| 안 지난다 | `SettlementSweeper`·`OutboxRelay` | 승자를 하나로 만드는 것이 따로 있다 — 정산은 `skip locked` 와 조건부 UPDATE, 발행은 트랜잭션이 쥔 `skip locked` 와 at-least-once(`D11`) |
| 안 지난다 | `OutboxCleaner`·`IdempotencyCleaner` | 두 번 돌아도 지울 것이 이미 없다 — 보존 기한 `delete` |
| 안 지난다 | `SeatVersionRepairScheduler` | 고칠 표시가 파드마다 제 메모리다 — 남의 것을 못 고친다(`I17-9`) |

## 재시도 — 다시 해서 달라지는 것만

| 무엇 | 재시도 | 왜 |
|---|---|---|
| 교착(`40P01`) | 1회 | 잠금 순서가 겹친 우연이다. 다시 하면 대개 된다 |
| 직렬화 실패(`40001`) | 1회 | 지금 격리 수준에서는 안 나지만 규칙은 같다 |
| 조건부 UPDATE 0행 | **안 한다** | 남이 이겼다는 확정 답이다. 다시 해도 같다 |
| 모의 PG 시간 초과 | **안 한다** | 승인됐는데 응답만 못 받았을 수 있다. 재시도가 이중 결제다 — 상태 조회로 푼다(16) |
| PG 멱등키 | **예매 id 로 묶는다** — `<reservation_id>:<Idempotency-Key>` | 우리 멱등은 계정마다라 남과 키 값이 겹쳐도 PG 까지 온다. 그대로 넘기면 PG 가 남의 승인을 돌려준다(`I17-2`) |
| 유일 제약 위반 | **안 한다** | 같은 값이 다시 와도 같다 |

## 트랜잭션 경계

서비스가 경계다(`D14`). 여기서 더하는 것:

| 규칙 | 왜 |
|---|---|
| 바깥 호출(모의 PG)은 트랜잭션 **밖**에서 | 안에서 부르면 응답을 기다리는 동안 좌석 행 잠금이 열려 있고, PG 가 늦으면 그 좌석을 보는 모든 요청이 기다린다 |
| 그래서 결제는 트랜잭션 셋 | ① `held → paying`(짧다) ② PG 호출(트랜잭션 없음) ③ 결과 반영(짧다). ②·③ 에서 죽으면 타임아웃이 `expired` 로 정리하고, ① 이 같이 적은 시도(`payment_attempt`)를 대사(`PaymentReconciler`, 1분)가 같은 PG 키로 묻는다 — 승인이면 `late` 결제 행 → 환불(`I17-1`). 대사와 원 요청이 겹치면 진 쪽은 기존 행을 읽는다. PG 가 모르는 키는 `started_at` 뒤 30분이 지나야 닫는다 — 그동안 매 회 다시 묻는다(`I17-11`) |
| 읽기 모델 갱신은 커밋 **뒤** | `TransactionSynchronization.afterCommit` 에서 Redis 버전을 올린다(`D20`). 커밋 전에 올리면 롤백된 변경을 화면이 본다 |

## 지금 안 하는 것

| 항목 | 왜 |
|---|---|
| 낙관락 `version` 컬럼 | 조건부 UPDATE 가 같은 일을 한다. **19 가 셋을 쟀다** — 1000 스레드에서 조건부 1 : 낙관 1.4 : 비관 4.3(p95 는 1 : 1.5 : 7.5)(ADR 0006, `doc/notes/lock-comparison.md`) |
| ~~Redisson 좌석 락~~ | **닫혔다 — ADR 0008**. 34 가 재 보니 Redis 락이 1000 스레드에서 전체 1.5~2배·p50 4~6배 느리다(왕복이 셋, 클라이언트가 커넥션 하나를 나눠 쓴다). 좌석은 DB 가 락이고, Redis 락은 스케줄러에만 쓴다(33) |
| 좌석 단위 대기열 | 대기열은 회차 단위다(`D12`). 좌석마다 줄을 세우면 줄이 2천 개다 |

## 이 문서를 고칠 때

**문장을 바꾸면 그 문장을 든 테스트를 같이 본다** — `SeatHoldConcurrencyTest`(13)·`HoldSweeperTest`(14)·`PaymentConfirmTest`(16).
격리 수준을 바꾸는 것은 위 §13.2.1 근거를 다시 쓰는 일이라 ADR 이다.
