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
| 회차 `open` 을 같은 문장에서 본다 | 닫힌 회차의 좌석도 `available` 로 남아 있다(`V6` 는 회차 상태를 모른다). 따로 읽으면 그 사이에 닫힌다 |
| `performance_id` 를 같이 건다 | 다른 회차의 좌석 id 를 섞어 보내는 요청이 그 회차의 좌석을 못 잡는다 |
| 상태 리터럴은 코드의 열거형에서 바인딩한다 | `D14` 「SQL」 |

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

### 선점의 문장 순서 — check 제약이 정했다

`performance_seat_held_fields_check`(`V6`)가 `held` 행에 `reservation_id` 를 요구한다. 그래서 순서는 이렇다:

```
1. reservation 행 삽입 (HELD, held_until)        → reservation_id 를 얻는다
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

**불변식**: 예매가 `HELD`·`PAYING`·`RESERVED` 면 그 예매의 `reservation_seat` 좌석 집합 = `performance_seat` 에서 `reservation_id` 가 그 예매인 집합.
`ReservationSeatConsistencyTest`(13)가 매 전이 뒤에 대조한다. 트리거로 안 내리는 이유는 두 표를 잇는 조건이라 행 트리거가 볼 자리가 없어서다.

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
| 형식 | UUIDv4 |
| 전달 | `Idempotency-Key` 요청 헤더 |
| 범위 | 계정별로 유일. 남의 키와 겹쳐도 상관없다 |
| 보관 | 24시간 |

| 어디에 | 요구 |
|---|---|
| `POST /api/reservations`(선점, 13) | **필수.** 없으면 400 `validation-failed` |
| `POST /api/reservations/{id}/payments`(결제, 16) | **필수** |
| `POST /api/reservations/{id}/cancel`(취소, 17) | **안 받는다** — 이미 있는 자원의 상태를 옮기는 것이라 조건부 UPDATE 가 둘째 요청을 0행으로 끝낸다 |
| 그 밖 | 안 받는다 |

돈이나 좌석이 움직이는 `POST` 에만 건다. 전부에 걸면 클라이언트가 의미 없는 키를 만든다.

### 서버가 하는 일

```
키가 처음이다         → 선점하고 처리한다. 끝나면 응답 본문을 저장한다
같은 키가 또 왔다     → 저장된 본문을 그대로 돌려준다 (상태 코드까지)
같은 키가 처리 중이다 → 앞 요청이 끝날 때까지 기다린다. lock_timeout(2초)을 넘기면 409 `idempotency-in-progress`
같은 키인데 본문이 다르다 → 422 `idempotency-key-reused`
```

본문 비교는 요청 본문의 해시로 한다. 본문 전체를 보관하지 않는다.

**키 선점·처리·응답 저장을 한 트랜잭션에 둔다.** 갈라 두면 「예매는 커밋됐는데 키 기록은 진행중」인 구간이 생기고,
그 구간에서 서버가 죽으면 재전송이 영영 409 를 받는다. 한 트랜잭션이면 어디서 죽든 전부 롤백이라 반쪽 상태가 없다.

## 스윕과 확정의 경합 — 상태로 푼다

`D3` 의 `PAYING` 이 답이다. 스윕은 `HELD` 만 훑고, 결제가 시작되면 예매가 `PAYING` 이라 스윕의 조건부 UPDATE 가 0행이다.

```sql
-- 스윕 (14). 둘 다 조건부라 두 번 돌아도 둘째는 0행이다
update reservation set status = 'EXPIRED', expired_at = now()
 where status = 'HELD' and held_until < now();

update performance_seat set status = 'available', held_until = null, reservation_id = null
 where status = 'held' and held_until < now()
   and reservation_id in (select reservation_id from reservation where status = 'EXPIRED' …);
```

**결제 승인도 조건부다** — `where status = 'PAYING' and paying_until >= now()`. 0행이면 승인을 받았어도 좌석을 못 주는 것이고,
그때는 모의 PG 에 취소를 보내고 `payment_late` 사건을 감사에 남긴다(16). 실제 PG 라면 여기가 자동 환불 자리다.

## 스케줄러 — 인스턴스 여럿에서 하나만

스윕·타임아웃·회차 종료는 전부 멱등이라 두 대가 같이 돌아도 결과는 같다. 그래도 **Redis 락으로 하나만 돌린다**(33, ADR 0003) —
같은 행을 두 번 스캔하는 비용이 있고, 로그가 두 벌 남으면 「몇 개를 만료시켰나」를 세는 사람이 헷갈린다.
락을 못 잡으면 그 회차는 건너뛴다. 기다리지 않는다.

## 재시도 — 다시 해서 달라지는 것만

| 무엇 | 재시도 | 왜 |
|---|---|---|
| 교착(`40P01`) | 1회 | 잠금 순서가 겹친 우연이다. 다시 하면 대개 된다 |
| 직렬화 실패(`40001`) | 1회 | 지금 격리 수준에서는 안 나지만 규칙은 같다 |
| 조건부 UPDATE 0행 | **안 한다** | 남이 이겼다는 확정 답이다. 다시 해도 같다 |
| 모의 PG 시간 초과 | **안 한다** | 승인됐는데 응답만 못 받았을 수 있다. 재시도가 이중 결제다 — 상태 조회로 푼다(16) |
| 유일 제약 위반 | **안 한다** | 같은 값이 다시 와도 같다 |

## 트랜잭션 경계

서비스가 경계다(`D14`). 여기서 더하는 것:

| 규칙 | 왜 |
|---|---|
| 바깥 호출(모의 PG)은 트랜잭션 **밖**에서 | 안에서 부르면 응답을 기다리는 동안 좌석 행 잠금이 열려 있고, PG 가 늦으면 그 좌석을 보는 모든 요청이 기다린다 |
| 그래서 결제는 트랜잭션 셋 | ① `HELD → PAYING`(짧다) ② PG 호출(트랜잭션 없음) ③ 결과 반영(짧다). ②에서 죽으면 ③이 안 돌고 타임아웃이 `EXPIRED` 로 정리한다 |
| 읽기 모델 갱신은 커밋 **뒤** | `TransactionSynchronization.afterCommit` 에서 Redis 버전을 올린다(`D20`). 커밋 전에 올리면 롤백된 변경을 화면이 본다 |

## 지금 안 하는 것

| 항목 | 왜 |
|---|---|
| 낙관락 `version` 컬럼 | 조건부 UPDATE 가 같은 일을 한다. 19 가 셋을 재서 ADR 0006 에 남긴다 |
| Redisson 좌석 락 | 34 가 33 환경에서 재서 ADR 0008 에 남긴다. 지금은 DB 가 락이다 |
| 좌석 단위 대기열 | 대기열은 회차 단위다(`D12`). 좌석마다 줄을 세우면 줄이 2천 개다 |

## 이 문서를 고칠 때

**문장을 바꾸면 그 문장을 든 테스트를 같이 본다** — `SeatHoldConcurrencyTest`(13)·`HoldSweeperTest`(14)·`PaymentConfirmTest`(16).
격리 수준을 바꾸는 것은 위 §13.2.1 근거를 다시 쓰는 일이라 ADR 이다.
