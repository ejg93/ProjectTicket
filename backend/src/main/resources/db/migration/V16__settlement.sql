-- 정산(청크 27, D21). 둘째 아웃박스 소비자다 — 다음 청크가 Kafka 를 든다(ADR 0001).
--
-- **값은 표에 두고 정산서는 항목의 합으로 만든다.** 요율·귀속·주기가 바뀌어도 코드가 안 바뀐다.

create table settlement_policy (
    settlement_policy_id bigint        generated always as identity primary key,

    -- null 이면 기본 정책. 기획사 행이 있으면 그것이 이긴다(D21 「고르는 규칙」).
    organizer_id         bigint        references organizer (organizer_id) on delete restrict,
    effective_at         timestamptz   not null,

    commission_rate      numeric(4, 2) not null,
    -- 취소 수수료 중 **기획사** 몫. 나머지는 플랫폼.
    cancel_fee_share     numeric(4, 2) not null,
    payout_delay_days    int           not null,

    created_at           timestamptz   not null default now(),

    -- 같은 대상의 같은 시행 시각이 둘이면 어느 것이 이기는지가 안 정해진다.
    -- `nulls not distinct` 라 기본 행(organizer_id null)도 판마다 하나다 — Postgres 15 부터 된다.
    constraint settlement_policy_key unique nulls not distinct (organizer_id, effective_at),
    constraint settlement_policy_commission_rate_check check (commission_rate >= 0 and commission_rate <= 1),
    constraint settlement_policy_cancel_fee_share_check check (cancel_fee_share >= 0 and cancel_fee_share <= 1),
    constraint settlement_policy_payout_delay_days_check check (payout_delay_days >= 0)
);

comment on table settlement_policy is '정산 정책(D21). 값을 바꾸는 것은 행을 더하는 일이고 코드는 안 바뀐다';

-- 시작값(ADR 0003·D21). 마이그레이션에 있는 이유는 동의 항목(V3)과 같다 — 이 행이 없으면 회차를 못 연다.
insert into settlement_policy (organizer_id, effective_at, commission_rate, cancel_fee_share, payout_delay_days)
values (null, '2026-01-01T00:00:00Z', 0.10, 1.00, 7);


-- **회차를 열 때 정책을 박제한다**(D21). 가격을 오픈 때 박제한 것과 같은 논리다(D7) —
-- 정책을 고쳐도 이미 열린 회차의 정산이 안 흔들리고, 새 정책은 다음 회차부터다.
alter table performance add column settlement_policy_id bigint references settlement_policy (settlement_policy_id) on delete restrict;

comment on column performance.settlement_policy_id is '오픈 때 박제한 정산 정책. draft 면 null 이다';


create table settlement (
    settlement_id        bigint      generated always as identity primary key,

    -- **회차당 정산서는 하나다.** 소비자가 사건을 두 번 받아도 둘째가 여기서 막힌다(멱등, D11).
    performance_id       bigint      not null references performance (performance_id) on delete restrict,
    settlement_policy_id bigint      not null references settlement_policy (settlement_policy_id) on delete restrict,

    -- 기획사 지급액 = Σ 항목. 아래 지연 트리거가 커밋 때 대조한다.
    amount               int         not null default 0,
    status               text        not null default 'scheduled',

    -- 집계 예정 시각 = 종료 + `payout_delay_days`. **박제**(D7) — 정책을 고쳐도 예약된 정산이 안 흔들린다.
    settle_at            timestamptz not null,
    settled_at           timestamptz,

    created_at           timestamptz not null default now(),

    constraint settlement_performance_id_key unique (performance_id),
    -- 음수 정산서는 안 만든다(D21 「지금 안 하는 것」 — 위약금 결정이 없다).
    constraint settlement_amount_check check (amount >= 0),
    constraint settlement_status_check check (status in ('scheduled', 'pending', 'confirmed', 'paid')),
    constraint settlement_status_fields_check check (
        status = 'scheduled' and settled_at is null and amount = 0
        or status <> 'scheduled' and settled_at is not null
    )
);

comment on table settlement is '회차당 정산서 하나(D21). scheduled 로 예약되고 settle_at 뒤에 집계된다';

-- 스케줄러가 집계할 것만 훑는다. 부분 인덱스라 이미 집계된 것은 안 들어간다.
create index settlement_scheduled_idx on settlement (settle_at) where status = 'scheduled';


create table settlement_line (
    settlement_line_id bigint      generated always as identity primary key,
    settlement_id      bigint      not null references settlement (settlement_id) on delete cascade,

    kind               text        not null,
    -- **부호가 있다.** 플랫폼 수수료는 음수고 매출·취소 수수료는 양수다(D21 「항목」).
    amount             int         not null,

    created_at         timestamptz not null default now(),

    -- 같은 종류가 둘이면 합이 두 번 센다. 종류를 더하는 것은 값 목록에 한 줄이지 항목 두 벌이 아니다.
    constraint settlement_line_key unique (settlement_id, kind),
    constraint settlement_line_kind_check check (kind in ('sale', 'platform_fee', 'cancel_fee', 'adjustment'))
);

comment on table settlement_line is '정산 항목. 부호가 있고 정산서 금액은 이것의 합이다(D21)';

-- 합계 불변식(D21): settlement.amount = Σ settlement_line.amount.
-- **지연이다** — 항목이 정산서보다 늦게 들어온다. `scheduled` 정산서는 항목이 없고 금액이 0 이라 0 = 0 으로 통과한다.
-- NEW 를 안 믿고 행을 다시 읽는다(V7·V10 과 같은 이유 — stack.md).
create or replace function settlement_total_matches_lines() returns trigger as $$
declare
    v_settlement_id bigint := coalesce(new.settlement_id, old.settlement_id);
    v_amount        int;
    v_sum           int;
begin
    select amount into v_amount from settlement where settlement_id = v_settlement_id;

    if not found then
        return null;
    end if;

    select coalesce(sum(amount), 0) into v_sum from settlement_line where settlement_id = v_settlement_id;

    if v_amount <> v_sum then
        raise exception '정산서 금액이 항목의 합과 다르다: settlement_id=%, amount=%, sum=%', v_settlement_id, v_amount, v_sum;
    end if;

    return null;
end;
$$ language plpgsql;

create constraint trigger settlement_total_check
    after insert or update on settlement
    deferrable initially deferred
    for each row execute function settlement_total_matches_lines();

create constraint trigger settlement_line_total_check
    after insert or update or delete on settlement_line
    deferrable initially deferred
    for each row execute function settlement_total_matches_lines();
