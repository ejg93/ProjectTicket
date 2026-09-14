# 환불 규약

관객이 취소하면 얼마를 돌려주고, 수수료는 어디로 가나. 구간표는 ADR 0003 이 정했고 이 문서는 그것을 **행으로 두고 계산과 박제를 정한다.**

## 구간표 `refund_fee_tier` — 행으로 둔다

| `days_before_min` | `rate` | 뜻 |
|---|---|---|
| 10 | 0.00 | 관람일 10일 전까지 무료 |
| 7 | 0.10 | 9~7일 전 |
| 3 | 0.20 | 6~3일 전 |
| 1 | 0.30 | 2~1일 전 |
| 0 | — | **당일은 취소 불가.** 행이 없다 — `cancel-window-closed` |

**고르는 규칙**: `days_before >= days_before_min` 인 행 중 `days_before_min` 이 가장 큰 것. `days_before` 는 `D7` 이 정한 KST 달력일 차다.

코드 상수가 아니라 행인 이유는 정산 정책과 같다 — 구간을 고치는 것이 배포가 아니어야 하고, `effective_at` 으로 개정 이력이 남아야 한다.
**경계는 KST 자정**이고 `RefundPolicyTest`(17)가 자정 앞뒤 1초를 잰다.

## 계산 — 예매 단위, 한 번 반올림

취소는 예매 전체다(ADR 0003, 부분 취소 없음). 그래서 수수료도 예매 합계에 한 번 매긴다.

```
fee_amount    = round(payment.amount × rate)      원 단위, half-up
refund_amount = payment.amount − fee_amount
```

좌석마다 매기면 합이 안 맞는다. 4석 × 154,000 × 10% 는 61,600 이고 좌석별 15,400 × 4 도 61,600 이지만, 율이 0.125 같은 값이면 갈린다.

## 환불 행 `refund` — 박제

| 열 | 뜻 |
|---|---|
| `payment_id` | 어느 결제를 되돌리나. **결제당 환불은 하나**(유일) |
| `reason` | `audience`(관객 취소) · `performance_cancelled`(회차 취소, 17a) · `payment_late`(승인이 늦어 좌석을 못 준 것, 16) |
| `days_before` | 취소 시점의 달력일 차. **박제** |
| `tier_rate` | 적용한 율. **박제** — 구간표를 개정해도 지난 환불이 안 바뀐다 |
| `fee_amount` · `refund_amount` | 계산 결과. 박제 |
| `status` | `requested → done`. 모의 PG 라 즉시 |

### 불변식

| 등식 | 어디서 |
|---|---|
| `fee_amount + refund_amount = payment.amount` | check 로 못 건다(다른 표). **지연 제약 트리거** |
| `fee_amount = round(payment.amount × tier_rate)` | `RefundPolicyTest` |
| `reason = 'performance_cancelled' → tier_rate = 0` | check |

## 사유별

| 사유 | 율 | 좌석 | 감사 |
|---|---|---|---|
| 관객 취소 | 구간표 | `available` 로 | `reservation.cancelled` |
| 회차 취소(17a) | **0.** 전액 | 회차가 `cancelled` 라 안 판다 | `reservation.cancelled` + `performance.cancelled` |
| 승인이 늦음(16) | 0. 전액. 관객은 좌석을 못 받았다 | 이미 스윕이 풀었다 | `payment_late` |

**결제 전 만료(`held`·`paying → expired`)는 환불이 아니다.** 돈을 안 받았다. 결제 행이 `declined`·`failed` 로 끝나고 환불 행은 없다.

## 수수료의 귀속

`fee_amount` 는 정산 규약(`D21`)의 `cancel_fee_share` 대로 나뉜다 — 시작값은 기획사 전부. 이 문서는 관객에게 얼마를 돌려주나까지고, 남은 돈이 누구 것인지는 정산이 정한다.

## 법과의 관계

전자상거래법 제17조제2항이 「시간이 지나 다시 판매하기 곤란할 정도로 재화등의 가치가 현저히 감소한 경우」를 청약철회 제한 사유로 두고, 사업자가 그 사실을 **사전에 명확히 고지**했을 것을 요건으로 한다 ([법제처 원문](https://www.law.go.kr/%EB%B2%95%EB%A0%B9/%EC%A0%84%EC%9E%90%EC%83%81%EA%B1%B0%EB%9E%98%EB%93%B1%EC%97%90%EC%84%9C%EC%9D%98%EC%86%8C%EB%B9%84%EC%9E%90%EB%B3%B4%ED%98%B8%EC%97%90%EA%B4%80%ED%95%9C%EB%B2%95%EB%A5%A0/%EC%A0%9C17%EC%A1%B0)). 공연 티켓이 그 예다.

우리가 고지하는 자리 둘:

| 자리 | 무엇 |
|---|---|
| 이용약관 제3조(`V3`) | 관람일 기준 수수료가 있고 당일 취소가 안 된다는 것 |
| 취소 화면(44) | **결제 전과 취소 전에** 구간표와 이번 취소의 수수료·환불액을 보여준다 |

**확인하지 않은 것**: 공정거래위원회 소비자분쟁해결기준의 공연업 항목이 정한 구간과 우리 표가 같은지. 실무 통상치로 정했고(ADR 0003) 조문 대조는 안 했다. 법률 검토가 필요해지면 그때 본다.

## 지금 안 하는 것

| 항목 | 왜 |
|---|---|
| 부분 취소 | ADR 0003. 수수료 기준과 정산 금액이 갈린다 |
| 수수료 면제(천재지변·공연 지연) | 사유 하나 더다. `reason` 값과 율 0 으로 받을 수 있는 자리는 있다 |
| 포인트·쿠폰 환불 | 결제 수단이 카드 하나다 |

## 이 문서를 고칠 때

**구간을 바꾸면 `refund_fee_tier` 에 행을 더한다.** 이 문서의 표는 시작값이고 실물은 DB 다. 계산식을 바꾸면 `RefundPolicyTest` 와 지연 트리거를 같이 본다.
