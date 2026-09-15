-- 모의 결제(청크 16). 예매 하나에 결제 행이 여럿일 수 있고(재시도) 승인은 하나뿐이다(D3 「결제」).
--
-- PG 를 부르기 전의 행은 없다 — 호출이 트랜잭션 밖이라(D4 「트랜잭션 경계」) 결과가 온 뒤 결과 반영 트랜잭션이 행을 만든다.
-- 카드번호는 여기 안 닿는다. 남는 것은 뒷 4자리뿐이다(D9).

create table payment (
    payment_id      bigint      generated always as identity primary key,
    reservation_id  bigint      not null references reservation (reservation_id) on delete restrict,

    status          text        not null,
    -- 예매 합계의 사본. 결제 시점에 얼마를 청구했나 — 예매가 뒤에 바뀌어도(안 바뀌지만) 기록은 기록 안에서 닫힌다.
    amount          int         not null,

    -- PG 가 채번한 승인번호. 승인일 때만 있다.
    approval_number text,
    -- 거절 사유 코드(insufficient_funds …) 또는 실패 사유(no_response). 승인이면 없다.
    decline_reason  text,
    card_last4      text        not null,

    created_at      timestamptz not null default now(),

    constraint payment_status_check check (status in ('approved', 'declined', 'failed')),
    constraint payment_amount_check check (amount >= 0),
    constraint payment_status_fields_check check (
        status = 'approved' and approval_number is not null and decline_reason is null
     or status = 'declined' and approval_number is null     and decline_reason is not null
     or status = 'failed'   and approval_number is null
    ),
    constraint payment_card_last4_format_check check (card_last4 ~ '^[0-9]{4}$'),
    -- PG 가 같은 승인번호를 두 번 줄 리 없다. 준다면 우리가 같은 승인을 두 번 적은 것이다.
    constraint payment_approval_number_key unique (approval_number)
);

comment on table payment is '모의 결제 결과. PG 호출 뒤에만 행이 생긴다(D3). 카드번호는 뒷 4자리만';

-- 승인은 예매당 하나(D3). 재시도가 두 번 승인을 적으면 여기서 막힌다 — 멱등키가 앞에서 막고 이것이 끝에서 한 번 더 막는다.
create unique index payment_approved_idx on payment (reservation_id) where status = 'approved';
-- 예매의 결제 이력을 읽는다. `restrict` 참조라 없으면 예매 삭제마다 이 표를 훑는다.
create index payment_reservation_idx on payment (reservation_id);

-- 결제 결과는 기록이다. 고칠 자리가 없어야 「그때 PG 가 뭐라 했나」가 그대로다. 되돌리는 것은 환불 행(17)이 든다.
create or replace function payment_append_only() returns trigger as $$
begin
    raise exception '결제 기록은 고칠 수 없다: payment_id=%', old.payment_id;
end;
$$ language plpgsql;

create trigger payment_no_update
    before update on payment
    for each row execute function payment_append_only();
