# 상태머신 카탈로그

자원마다 어떤 상태가 있고, 누가 무엇을 조건으로 옮기며, 무엇이 저절로 옮겨지나.

**전이표는 코드에 선언하고 DB 트리거가 되돌리기를 막는다.** `performance` 가 이미 그 모양이다(`V5`) —
코드에만 두면 `psql`·배치로 들어오는 변경을 못 막고, 되돌리기가 특히 위험하다.

**전이 조건의 단일 출처가 이 문서다.** 여기 없으면 다음 사람은 서비스 코드를 읽어야 조건을 알고, 그러면 이 문서를 읽을 이유가 없어진다.

## 예매 `reservation`

```
held ──결제 시작──> paying ──승인──> reserved ──취소──> cancelled
  │                   │
  ├─5분 만료(스윕)──> expired
  │                   ├─3분 타임아웃────> expired
  │                   └─거절·실패──────> held   (남은 선점 시간이 있을 때만)
  └─회차 종료·취소──> expired / cancelled
```

| 상태 | 뜻 | 좌석은 | 시간 제한 | 누가 옮기나 |
|---|---|---|---|---|
| `held` | 좌석을 잡았다. 결제 전이다 | `held` | `held_until` = 선점 + 5분(ADR 0003) | 관객이 선점(13) |
| `paying` | 결제를 시작했다. **스윕이 안 건드린다** | `held` | `paying_until` = 시작 + 3분(ADR 0004) | 관객이 결제 시작(16) |
| `reserved` | 결제가 승인됐다. 예매가 성립했다 | `reserved` | 없음 | 결제 승인(16) |
| `cancelled` | 관객이 취소했거나 회차가 취소됐다 | `available` | 없음 | 관객(17)·기획사(17a) |
| `expired` | 결제 전에 시간이 지났거나 회차가 닫혔다 | `available` | 없음 | 스윕(14)·타임아웃(16)·회차 종료(16a) |

### 전이표

시각·결제 조건(`held→paying`·`paying→reserved`·`paying→held` 의 시각, `reserved` 의 승인 결제)은 `V28` 트리거가 든다. 만료·취소는 시각과 무관하다.

| 에서 | 로 | 조건 | 어디서 |
|---|---|---|---|
| — | `held` | 좌석 N 개 전부 `available` 이고 회차가 `open` 이고 `sales_open_at <= now()` 이고 활성 토큰이 있다 | 13 |
| `held` | `paying` | `held_until` 이 안 지났다. 회차가 `open` 이다 | 16 |
| `held` | `expired` | `held_until < now()` | 14 스윕 |
| `paying` | `reserved` | 결제 승인. `paying_until` 이 안 지났다. 회차가 `open` 이다 | 16 |
| `paying` | `held` | 결제 거절·실패. `held_until` 이 안 지났다 | 16 |
| `paying` | `expired` | `paying_until < now()`, 또는 거절됐는데 `held_until` 도 지났다 | 16 타임아웃 |
| `reserved` | `cancelled` | 관람일 당일이 아니다(ADR 0003 수수료 구간) | 17 |
| `held`·`paying` | `expired` | 회차가 `closed` 로 간다 | 16a |
| `held`·`paying`·`reserved` | `cancelled` | 회차가 `cancelled` 로 간다. `reserved` 는 전액 환불 | 17a |

**`paying` 이 있는 이유는 스윕과 승인의 경합이다**(ADR 0004). 상태가 `held` 하나면 스윕이 T 에 좌석을 풀고
승인이 T+ε 에 와서 **돈은 받고 좌석은 없는** 예매가 된다. 결제가 시작되면 `paying` 으로 옮기고 스윕은 `held` 만 훑으므로
같은 행을 두고 다투는 자리가 사라진다. 대신 `paying` 이 영영 남지 않게 자체 타임아웃을 둔다.

**`paying → held` 는 되돌리기가 아니라 재시도 허용이다.** 카드가 거절돼도 선점 시간이 남았으면 다른 카드로 다시 시도한다.
`held_until` 은 그대로다 — 결제 시도가 선점을 연장하지 않는다.

**끝 상태는 `reserved`·`cancelled`·`expired` 다.** `expired`·`cancelled` 에서 나가는 전이는 없다.

**`cancelled`·`expired` 로 갈 때는 `cancelled_by` 를 같이 쓴다**(26a) — `audience`·`organizer`·`expired` 셋이고 전이 트리거가 요구한다.
상태만으로는 관객이 먼저 무른 것과 회차 취소로 물린 것이 안 갈리는데, 앞쪽은 수수료를 뗀 부분 환불이라 알림·정산·조회가 말을 다르게 해야 한다.
다시 사려면 새 예매다.

### 좌석과의 대응

`performance_seat.status` 는 예매 상태의 **투영**이다. 예매가 진실이고 좌석은 그것을 좌석 단위로 편 것이다.

| 예매 | 좌석 | 좌석의 `reservation_id` |
|---|---|---|
| `held`·`paying` | `held` | 그 예매 |
| `reserved` | `reserved` | 그 예매 |
| `cancelled`·`expired` | `available` | null |

**둘이 갈리는 것은 `D4` 의 대조 테스트가 막는다.** 갈릴 수 있는 자리는 전이 하나가 두 표를 고치는 자리 전부이고,
그래서 전이는 반드시 한 트랜잭션이다.

발권(`ticket`)은 `reserved` 예매의 좌석에만 선다 — `ticket_requires_reserved`(`V28`)가 막는다.

## 좌석 `performance_seat`

예매의 투영이라 상태가 셋뿐이다(위 「좌석과의 대응」). 전이는 `performance_seat_status_transition`(`V26`)이 막고,
포인터가 다른 예매로 넘어가는 것은 `performance_seat_no_handover`(`V24`)가 막는다.

### 전이표

| 에서 | 로 | 조건 | 청크 |
|---|---|---|---|
| `available` | `held` | 선점. 회차가 `open` 이다 | 13 |
| `held` | `reserved` | 예매가 `reserved` 로 간다 | 16 |
| `held` | `available` | 예매가 `expired`·`cancelled` 로 가거나 결제가 실패해 선점이 끝났다 | 14·16·16a·17a |
| `reserved` | `available` | 예매가 `cancelled` 로 간다 | 17·17a |

## 결제 `payment`

| 상태 | 뜻 |
|---|---|
| `approved` | 승인됐다. 예매가 `reserved` 로 간다 |
| `declined` | 거절됐다(잔액 부족 등). 예매는 `held` 로 돌아간다 |
| `failed` | 응답이 없거나 깨졌다. 예매는 `held` 로 돌아간다 — 재시도할 수 있다 |

**PG 를 부르기 전의 행은 없다.** 호출이 트랜잭션 밖이라(`D4` 「트랜잭션 경계」) 적을 트랜잭션이 없고, 결과가 온 뒤 결과 반영 트랜잭션이 행을 만든다.
무응답은 재시도가 아니라 **같은 키로 PG 에 상태를 묻는다** — 승인됐는데 응답만 잃은 것이면 그 승인을 쓰고, PG 도 모르면 `failed` 다(`D4` 「재시도」).

예매 하나에 결제 행이 여럿일 수 있다(재시도). **승인은 하나뿐이다** — `approved` 는 예매당 부분 유일 인덱스로 막는다.
결제 결과가 무엇인지는 카드번호가 정한다(ADR 0003, 모의 PG).

## 환불 `refund`

| 상태 | 뜻 |
|---|---|
| `requested` | 취소가 접수됐다. 수수료와 환불액이 계산됐다 |
| `done` | 환불이 나갔다. 모의 PG 라 즉시다 |

**환불액 계산은 `D6`(`refund-policy.md`) 이 정한다.** 관람일 기준 구간이고, 회차 취소(17a)는 구간과 무관하게 전액이다.

### 전이표

| 에서 | 로 | 조건 | 어디서 |
|---|---|---|---|
| — | `requested` | 결제당 하나(`refund_payment_id_key`) | 17·17a |
| `requested` | `done` | 환불 번호가 붙었다 | 17·17a |

## 정산 `settlement`

상태의 뜻·금액·집계 시각은 `D21`(`settlement-rules.md`)이 정한다. 전이는 이 표가 단일 출처다.

### 전이표

| 에서 | 로 | 조건 | 어디서 |
|---|---|---|---|
| — | `scheduled` | 회차 종료·취소 사건. 회차당 하나 | 27 소비자 |
| `scheduled` | `pending` | `settle_at <= now()`(`V30` 트리거). 항목의 합으로 금액이 선다 | 27 집계 |
| `pending` | `confirmed` | 관리자 확인. 부르는 코드가 아직 없다(D21 「누가 보나」) | — |
| `confirmed` | `paid` | 지급. 부르는 코드가 아직 없다 | — |

## 회차 `performance`

```
draft ──오픈──> open ──판매 마감──> closed
                 │
                 └──기획사 취소──> cancelled
```

| 상태 | 뜻 | 누가 옮기나 |
|---|---|---|
| `draft` | 만들어졌다. 좌석 복제 전이다. **회차는 이 상태로만 태어난다**(`V5` 삽입 가드) | — |
| `open` | 좌석이 복제됐고 판다 | 기획사(9·11) |
| `closed` | `sales_close_at` 이 지났다. 남은 `held`·`paying` 은 `expired` 로 | 스케줄러(16a) |
| `cancelled` | 기획사가 취소했다. 모든 예매가 `cancelled`, `reserved` 는 전액 환불 | 기획사(17a) |

### 전이표

| 에서 | 로 | 조건 | 어디서 |
|---|---|---|---|
| — | `draft` | 회차는 이 상태로만 태어난다(`V5` 삽입 가드) | 9 |
| `draft` | `open` | 좌석이 복제됐다 | 11 |
| `open` | `closed` | `sales_close_at < now()`(`V30` 트리거) | 16a |
| `open` | `cancelled` | 기획사가 무른다. `closed` 뒤로는 없다 | 17a |

`sales_close_at` 의 기본은 `starts_at - 1시간` 이고 `sales_open_at < sales_close_at < starts_at` 을 check 가 든다(16a).
`cancelled` 는 17a 가 check 목록과 전이 트리거에 더한다 — `open` 에서만 간다. `closed` 뒤의 취소는 없다.

## 알림 `notification`

| 상태 | 뜻 |
|---|---|
| `pending` | 사건을 받아 본문을 적었다. 아직 안 보냈다 |
| `sent` | 보냈다. 모의 발송이라 이 표의 본문이 보낸 것이다 |
| `failed` | 보내다 실패했다. 사유가 `failure_reason` 에 있다 |
| `skipped` | 안 보내기로 했다(탈퇴한 계정 등). 사유가 `failure_reason` 에 있다 |

전이는 `notification_status_transition`(`V29`)이 막는다. 끝 상태 셋에서 나가는 전이는 없다 — 실패를 다시 보내는 경로가 생기면 표와 트리거에 같이 더한다.

### 전이표

| 에서 | 로 | 조건 | 어디서 |
|---|---|---|---|
| — | `pending` | 사건·계정당 하나(`notification_event_account_key`) | 26 |
| `pending` | `sent` | 발송 성공 | 26 |
| `pending` | `failed` | 발송 실패 | 26 |
| `pending` | `skipped` | 받을 계정이 없다 | 26 |

## 대기열 토큰 (Redis)

DB 상태기계가 아니다. `D12`(`queue-design.md`)가 정한다. 요약만 둔다:

```
WAITING ──입장──> ADMITTED ──선점 성공──> (정원 반납, 토큰은 45초 유예 — 23a)
   │                 └──TTL 10분──> (만료)
   └──하트비트 90초 없음──> (제거)
```

## 자동 전이 셋

| 무엇 | 언제 | 어떻게 |
|---|---|---|
| 선점 만료 스윕 | 30초마다(ADR 0003) | `held` 이고 `held_until < now()` 인 예매를 `expired`, 좌석을 `available`. **조건부 UPDATE** 라 그 사이에 `paying` 으로 간 것은 안 건드린다 |
| 결제 타임아웃 | 30초마다, 같은 스케줄러 | `paying` 이고 `paying_until < now()` → `expired` |
| 회차 종료 | 1분마다(16a) | `open` 이고 `sales_close_at < now()` → `closed`, 남은 `held`·`paying` → `expired` |

**셋 다 멱등이다.** 두 번 돌아도 둘째는 0행이다. 인스턴스 여럿에서 동시에 돌 때는 Redis 락(33)이 하나만 돌린다 —
안 돌려도 결과는 같지만 같은 행을 두 번 스캔한다.

## 누가 옮기나

| 주체 | 옮길 수 있는 것 |
|---|---|
| 관객 | 자기 예매만 — 선점·결제 시작·취소 |
| 기획사 | 자기 공연의 회차만 — 오픈·취소 |
| 스케줄러 | 시간이 정한 것만 — 스윕·타임아웃·종료 |
| 관리자 | **강제 전이가 없다.** 관리자가 예매를 임의로 옮기는 자리를 안 만든다 — 필요해지면 그때 감사와 함께 청크로 |

## 상태 이력

`reservation` 은 상태 이력 표를 따로 두지 않는다. 전이마다 `audit_log` 에 `reservation.<전이>` 사건을 남기고(4),
`reserved`·`cancelled`·`expired` 시각은 예매 행의 열(`reserved_at`·`cancelled_at`·`expired_at`)이 든다.
**분쟁에서 묻는 것은 「언제 확정됐나」「언제 취소했나」 둘이라** 열이면 충분하고, 이력 표는 조회 한 번을 더 만든다.

## 어긋남은 트리거가 막되 상태를 안 옮긴다

트리거는 **불가능한 전이를 거절**한다(`performance_status_transition` 이 그 모양). 트리거가 상태를 대신 옮기지는 않는다 —
옮기면 어느 코드가 옮겼는지 감사 로그가 못 답한다. 옮기는 것은 서비스고, 트리거는 문지기다.

## 이 문서를 고칠 때

**상태를 더하면 셋을 같이 본다** — check 제약의 목록, 전이 트리거, 이 문서의 전이표.
셋 중 하나만 고치면 그날 어느 한쪽이 조용히 뒤처진다. `StateMachineDocTest`(`I3-1`)가 두 절의 전이표와 전이 트리거를 대조한다 — 트리거를 새로 만들면 표가 없다고 먼저 빨개진다.
