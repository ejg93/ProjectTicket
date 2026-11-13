-- PG 에 보낸 시도를 PG 를 부르기 **전에** 적는다(점검 17차 → `I17-1`).
--
-- 결제 행(`V9`)은 PG 가 답한 뒤 결과 반영 트랜잭션이 만든다. 그 트랜잭션이 죽으면(DB 순간 장애·풀 고갈·종료 단계를 넘긴 파드)
-- PG 는 승인했는데 우리 표에는 아무것도 없다 — 같은 키 재시도는 `paying` 이라 막히고, 만료 스윕이 `expired` 로 옮기고,
-- 환불 큐(`queueLatePayments`)는 승인된 `payment` 행을 찾으므로 환불도 없다. 이 표가 「PG 에 무엇을 물어봐야 하나」를 남긴다.
--
-- 시도마다 한 행이다 — 거절 뒤 다른 카드로 다시 결제해도 앞 시도가 안 덮인다. 행은 `startPaying`(`held → paying`)과 같은 트랜잭션에 서고,
-- 결과 반영(`settle`)이 같은 트랜잭션에서 `settled_at` 을 찍는다. 남은 행은 대사(`PaymentReconciler`)가 PG 에 묻는다.
-- 권한은 `V20` 의 `alter default privileges` 가 준다.

create table payment_attempt (
    payment_attempt_id bigint      generated always as identity primary key,
    reservation_id     bigint      not null references reservation (reservation_id) on delete restrict,
    account_id         bigint      not null references account (account_id) on delete restrict,
    -- PG 에 넘긴 멱등키 `<reservationId>:<멱등키>`(`I17-2`). bigint 19자 + `:` + UUID 36자 = 56자라 64 로 묶는다.
    gateway_key        text        not null,
    amount             int         not null,
    started_at         timestamptz not null default now(),
    -- 결과가 우리 표에 닿은 시각. 비어 있으면 대사 대상이다.
    settled_at         timestamptz,

    constraint payment_attempt_gateway_key_key unique (gateway_key),
    constraint payment_attempt_gateway_key_length_check check (char_length(gateway_key) between 1 and 64),
    constraint payment_attempt_amount_check check (amount >= 0)
);

comment on table payment_attempt is 'PG 에 보낸 결제 시도. PG 호출 전에 선다(I17-1). settled_at 이 비면 대사가 PG 에 묻는다';
comment on column payment_attempt.gateway_key is 'PG 멱등키 <reservationId>:<Idempotency-Key>. 같은 키로 PG 에 상태를 묻는다';
comment on column payment_attempt.settled_at is '결과 반영(결제 행) 또는 대사가 닫은 시각. null 이면 미결';

-- 대사가 미결만 훑는다. 닫힌 시도가 대부분이라 부분 인덱스다.
create index payment_attempt_unsettled_idx on payment_attempt (payment_attempt_id) where settled_at is null;
-- 예매 삭제·이력 조회. `restrict` 참조라 없으면 예매 삭제마다 이 표를 훑는다.
create index payment_attempt_reservation_idx on payment_attempt (reservation_id);
