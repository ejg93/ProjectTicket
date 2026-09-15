-- 취소 수수료 구간과 환불(청크 17, D6).
--
-- 구간을 코드 상수가 아니라 행으로 둔다 — 구간을 고치는 것이 배포가 아니어야 하고, `effective_at` 으로 개정 이력이 남아야 한다(D6·D21).
-- 시작 행이 마이그레이션에 있는 이유는 동의 항목(V3)과 같다: 이 행이 없으면 취소가 못 돌아서 시드가 아니라 스키마의 일부다.

create table refund_fee_tier (
    refund_fee_tier_id bigint        generated always as identity primary key,

    -- 이 구간의 시작. `days_before >= days_before_min` 인 행 중 가장 큰 것을 고른다(D6). 0 행이 없는 것이 「당일 취소 불가」다.
    days_before_min    int           not null,
    rate               numeric(4, 2) not null,
    -- 개정하면 새 판(같은 effective_at)의 행들을 넣고 옛 판은 남긴다. 고르는 것은 `effective_at <= now()` 인 최신 판이다.
    effective_at       timestamptz   not null,

    created_at         timestamptz   not null default now(),

    constraint refund_fee_tier_days_before_min_check check (days_before_min >= 0),
    constraint refund_fee_tier_rate_check check (rate >= 0 and rate <= 1),
    constraint refund_fee_tier_key unique (effective_at, days_before_min)
);

comment on table refund_fee_tier is '취소 수수료 구간표(D6). 실물은 이 표고 문서는 시작값이다';

-- 시작값(ADR 0003·D6). 저장소 시작일부터 효력.
insert into refund_fee_tier (days_before_min, rate, effective_at) values
    (10, 0.00, '2026-01-01T00:00:00Z'),
    (7,  0.10, '2026-01-01T00:00:00Z'),
    (3,  0.20, '2026-01-01T00:00:00Z'),
    (1,  0.30, '2026-01-01T00:00:00Z');


-- 환불. 결제당 하나(D6). 율·일수·금액을 박제한다 — 구간표를 개정해도 지난 환불이 안 바뀐다(D7 「계산한 기한은 박제한다」).
create table refund (
    refund_id     bigint        generated always as identity primary key,
    payment_id    bigint        not null references payment (payment_id) on delete restrict,

    reason        text          not null,
    -- 취소 시점의 KST 달력일 차(D7). 박제
    days_before   int           not null,
    -- 적용한 율. 박제
    tier_rate     numeric(4, 2) not null,
    fee_amount    int           not null,
    refund_amount int           not null,

    status        text          not null default 'requested',
    -- PG 가 채번한 환불 거래번호. 나갔을 때만 있다.
    refund_number text,
    done_at       timestamptz,

    created_at    timestamptz   not null default now(),

    constraint refund_payment_id_key unique (payment_id),
    constraint refund_reason_check check (reason in ('audience', 'performance_cancelled', 'payment_late')),
    constraint refund_status_check check (status in ('requested', 'done')),
    constraint refund_amounts_check check (fee_amount >= 0 and refund_amount >= 0),
    constraint refund_tier_rate_check check (tier_rate >= 0 and tier_rate <= 1),
    -- 회차 취소·승인 지연은 관객 잘못이 아니라 전액이다(D6 「사유별」).
    constraint refund_full_for_non_audience_check check (reason = 'audience' or tier_rate = 0 and fee_amount = 0),
    constraint refund_status_fields_check check (
        status = 'requested' and refund_number is null     and done_at is null
     or status = 'done'      and refund_number is not null and done_at is not null
    )
);

comment on table refund is '환불. 결제당 하나. 율·금액 박제(D6)';

-- 환불액 등식(D6): fee_amount + refund_amount = payment.amount. 다른 표라 check 로 못 걸고 지연 트리거다.
-- NEW 를 안 믿고 행을 다시 읽는다(V7 의 합계 트리거와 같은 이유).
create or replace function refund_amounts_match_payment() returns trigger as $$
declare
    v_fee    int;
    v_refund int;
    v_paid   int;
begin
    select r.fee_amount, r.refund_amount, p.amount into v_fee, v_refund, v_paid
      from refund r join payment p on p.payment_id = r.payment_id
     where r.refund_id = new.refund_id;

    if not found then
        return null;
    end if;

    if v_fee + v_refund <> v_paid then
        raise exception '환불 등식이 안 맞는다: refund_id=%, fee=%, refund=%, paid=%', new.refund_id, v_fee, v_refund, v_paid;
    end if;

    return null;
end;
$$ language plpgsql;

create constraint trigger refund_amounts_check_trigger
    after insert or update on refund
    deferrable initially deferred
    for each row execute function refund_amounts_match_payment();
