# 이벤트 카탈로그

아웃박스(25)로 나가는 사건의 이름·봉투·페이로드·버전, 그리고 그것을 받는 소비자(26 알림·27 정산)의 규약. 왜 아웃박스인지는 ADR 0001, 발행·소비 절차는 25·29.

**사건은 커밋된 사실이다.** 감사 로그(`audit_log`)와 이름은 같지만 목적이 다르다 — 감사는 「누가 무엇을 했나」의 기록이고, 사건은 **다른 소비자가 그것에 반응하기 위한 계약**이다.
그래서 소비자가 없는 사건은 안 낸다(`CLAUDE.md` 「부르는 곳을 세고 만든다」).

## 봉투

```json
{
  "event_id": "018f6b2e-7c4a-7d3e-9a1b-4f2c8e6d5a70",
  "type": "reservation.reserved",
  "version": 1,
  "occurred_at": "2026-09-25T10:00:00Z",
  "aggregate_type": "reservation",
  "aggregate_id": 1042,
  "payload": { }
}
```

| 필드 | 뜻 |
|---|---|
| `event_id` | UUID. **소비자 멱등의 키** — 같은 사건이 두 번 와도 한 번 처리한다(29) |
| `type` | `<집합체>.<과거형>` 소문자 점 표기. 감사 `event_type` 과 같은 이름 |
| `version` | 페이로드 판. 호환되는 변경은 안 올린다(아래 「버전」) |
| `occurred_at` | 사건이 일어난 트랜잭션의 DB 시각(`D7`). 발행 시각이 아니다 |
| `aggregate_type`·`aggregate_id` | 파티션 키(28) — 한 집합체의 사건은 순서를 지킨다 |
| `payload` | 아래 표. **식별자와 그때의 값**만 든다 |

**CloudEvents 를 안 쓴다.** 소비자가 우리뿐이라 표준 봉투가 사 줄 것이 없다. 다만 위 넷(`event_id`·`type`·`occurred_at`·`aggregate_*`)은 CloudEvents 의 `id`·`type`·`time`·`source` 와 1:1 이라, 밖으로 내보낼 날(웹훅)이 오면 감싸기만 하면 된다.

## 페이로드 규칙

| 규칙 | 왜 |
|---|---|
| **식별자 + 그때의 값** — 금액·좌석 수처럼 뒤에 못 되돌리는 값은 담고, 티켓 번호·이메일처럼 표에서 다시 읽을 수 있는 것은 안 담는다 | 사건이 커지면 대역폭이고, 표에 있는 것을 복사하면 두 벌이 된다 |
| **개인정보 없음** — 이메일·이름·카드 뒷자리를 안 담는다. `account_id` 만 | 사건은 브로커·DLQ·로그에 남는다(`D10`). 알림 소비자는 보낼 때 표에서 읽는다 — 탈퇴한 계정에는 그래서 안 간다 |
| 필드는 snake_case, 시각은 UTC `Z`, 금액은 정수 원 | `D5` 「값의 형식」과 같다 |
| 페이로드 `data class` 는 발행하는 패키지가 든다 | 소비자가 발행자의 타입을 import 한다 — 계약이 한 자리에 있다 |

## 카탈로그

소비자가 있는 사건만이다. 「누가 받나」가 비면 이 표에 못 든다.

| `type` | 언제 (트랜잭션) | `payload` | 누가 받나 |
|---|---|---|---|
| `reservation.reserved` | 결제 승인 반영(`PaymentTransitionService.confirm`) — 예매 `reserved`, 좌석 `reserved`, 발권과 같은 트랜잭션 | `reservation_id`·`account_id`·`performance_id`·`payment_id`·`total_amount`·`seat_count` | 26 확정 메일 · 27 정산 매출 |
| `reservation.cancelled` | 관객 취소(`RefundTransitionService.request`) | `reservation_id`·`account_id`·`performance_id`·`refund_id`·`reason`(`audience`)·`fee_amount`·`refund_amount` | 26 취소 메일 · 27 취소 수수료 |
| `performance.closed` | 판매 마감 자동 종료(16a) — `open → closed`, 남은 `held`·`paying` 만료와 같은 트랜잭션 | `performance_id`·`closed_at` | 27 정산서 생성(`payout_delay_days` 뒤) |
| `performance.cancelled` | 기획사 회차 취소(17a) — 모든 예매 취소·전액 환불과 같은 트랜잭션 | `performance_id`·`cancelled_reservation_count` | 26 회차 취소 메일(예매자 전원 — 소비자가 표에서 읽는다) · 27 정산 없음 표시 |

**아직 안 내는 것**과 그 이유:

| 사건 | 왜 안 내나 |
|---|---|
| `reservation.held`·`paying`·`expired` | 받는 곳이 없다. 좌석도 갱신은 사건이 아니라 `D20` 의 Redis 버전(10a)이 한다 |
| `payment.*` | 결제 결과는 `reservation.reserved` 에 `payment_id` 로 들어 있다. 거절·실패는 예매 상태를 안 바꾸고 소비자도 없다 |
| `refund.done` | 취소 사건에 환불 금액이 있다. 환불 완료 시각이 필요한 소비자가 생기면 그때 |
| `ticket.issued` | 확정과 같은 트랜잭션이라 `reservation.reserved` 가 곧 발권이다 |
| `payment.late` | 소비자가 스윕(17b)이지 사건이 아니다 |

## 버전

| 변경 | `version` | 어떻게 |
|---|---|---|
| 필드를 더한다 | 그대로 | 소비자는 모르는 필드를 무시한다(`D5` 「null 과 생략」과 같다) |
| 필드를 빼거나 뜻을 바꾼다 | +1 | 옛 판을 받는 소비자가 남아 있는 동안 **두 판을 같이 낸다.** 소비자가 전부 옮긴 뒤 옛 판을 끊는다 |
| `type` 을 바꾼다 | 새 사건 | 이름은 계약이다 — 오류 `type` 슬러그와 같은 취급 |

버전은 봉투에만 있고 `type` 에 안 붙인다(`reservation.reserved.v2` 로 안 쓴다). 파티션 키가 이름을 안 타야 한 집합체의 옛 판·새 판이 같은 순서로 간다.

## 발행 — 25

- 사건 행(`outbox`)은 **그 사실을 만든 트랜잭션 안에서** 넣는다. 확정 없이 사건 없고, 사건 없이 확정 없다 — `OutboxAtomicityTest`
- 릴레이가 `for update skip locked` 로 가져가 발행한다. 인스턴스 셋이 같은 행을 두 번 안 민다
- 발행은 **at-least-once** 다. 릴레이가 발행 뒤 표시 전에 죽으면 같은 사건이 다시 나간다 — 그래서 소비자가 `event_id` 로 멱등이어야 한다

## 소비 — 26·27·29

| 규칙 | 왜 |
|---|---|
| 소비자마다 `event_id` 유일 제약을 든다(`notification.event_id`·`settlement_line.event_id`) | 멱등을 앱 검증이 아니라 제약으로. 두 번 온 사건은 유일 위반의 `on conflict do nothing` 0행으로 끝난다 |
| 소비자는 페이로드의 식별자로 **표를 다시 읽는다** | 사건은 「무슨 일이 났나」고 지금 상태는 표가 안다. 취소 메일이 나갈 때 예매가 이미 다른 상태면 표가 이긴다 |
| 처리 실패는 재시도, 초과하면 DLQ(29) | 소비자 하나의 결함이 발행자를 멈추지 않는다 |
| 한 사건에 소비자가 둘이면 서로 모른다 | 알림이 죽어도 정산은 간다 |

## 알림 규약 — 26

| 항목 | 값 |
|---|---|
| 채널 | 이메일 하나. 모의 발송 — 보내는 대신 `notification` 행에 본문을 남긴다 |
| 무엇을 | `reservation.reserved` → 확정(좌석·금액·티켓 번호), `reservation.cancelled` → 취소(환불 금액·일수), `performance.cancelled` → 회차 취소(예매자 전원, 전액 환불) |
| 받는 사람 | 사건의 `account_id` 로 **보낼 때** 표에서 읽는다. 탈퇴(`deleted_at`)면 안 보낸다 |
| 멱등 | `(event_id, account_id)` 유일 — 회차 취소는 사건 하나에 수신자 여럿이라 계정이 키에 든다 |
| 문구 | 존댓말, 화면 규약(`D17`)과 같은 문체. 본문은 이력이라 고치지 않는다 |
| 안 하는 것 | 선점·만료 알림(화면의 카운트다운이 그 자리다), 마케팅(동의 항목은 있으나 보낼 것이 없다) |

## 강제 지점

| 무엇 | 어디 |
|---|---|
| 코드가 내는 `type` ⊆ 이 표 | `EventCatalogTest`(25) — 발행 코드의 `EventType` 열거형과 이 표를 대조한다 |
| 페이로드 모양 | 스냅샷(`D8`) — `reservation.reserved`·`cancelled` 의 JSON 을 굳힌다. 필드를 빼면 빨개진다 |
| 소비자 멱등 | 유일 제약 + `ConsumerIdempotencyTest`(29) |

## 이 문서를 고칠 때

**표에 줄을 더하려면 「누가 받나」를 먼저 채운다.** 비면 안 낸다. 페이로드에 개인정보를 넣으려는 순간 이 문서가 아니라 `D10` 을 본다 — 답은 「안 넣는다」다.
