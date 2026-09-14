-- 공연과 회차, 그리고 등급.
--
-- **등급은 공연 단위, 매핑은 구역 단위다**(ADR 0003). 가격은 공연이 정하는 것이고, 같은 공연의 회차끼리 값이 갈릴 이유가 없다.
-- 좌석 단위로 매핑하면 행이 좌석 수만큼 생겨서 시드·화면·복제가 모두 무거워진다.

create table event (
    event_id     bigint generated always as identity primary key,
    organizer_id bigint      not null references organizer (organizer_id) on delete restrict,

    title        text        not null,

    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),

    constraint event_title_length_check check (length(title) between 1 and 200)
);

create index event_organizer_idx on event (organizer_id);

create trigger event_set_updated_at
    before update on event
    for each row execute function set_updated_at();

-- 등급과 가격. 금액은 원 단위 정수다(ADR 0003) — 소수를 두면 반올림 규칙이 필요해지고, 원화에는 그 자리가 없다.
create table seat_grade (
    seat_grade_id bigint generated always as identity primary key,
    event_id      bigint      not null references event (event_id) on delete cascade,

    -- 코드가 안정된 키다. 이름은 화면에 보이는 것이라 바뀔 수 있다.
    code          text        not null,
    name          text        not null,

    -- 부가세 포함 판매가. **회차 오픈 때 `performance_seat` 에 박제된다**(청크 9) —
    -- 오픈 뒤에 여기를 고쳐도 이미 열린 회차의 가격은 안 바뀐다. 산 사람과 살 사람의 가격이 갈리면 안 된다.
    price         int         not null,

    sort_no       int         not null default 0,

    created_at    timestamptz not null default now(),

    constraint seat_grade_event_code_key unique (event_id, code),
    constraint seat_grade_price_check check (price >= 0),
    constraint seat_grade_code_format_check check (code ~ '^[A-Z][A-Z0-9]{0,9}$')
);

comment on column seat_grade.price is '부가세 포함 판매가, 원 단위 정수. 회차 오픈 때 박제된다';

-- 구역 → 등급. **공연 단위로 든다.**
--
-- 한 공연이 회차마다 다른 홀을 쓸 수 있어서, 여기에 여러 홀의 구역이 섞여 들어온다.
-- **그 홀의 모든 구역이 덮였는지는 스키마로 못 본다** — 회차가 어느 홀인지는 오픈할 때 정해진다.
-- 그 검사는 청크 9 의 오픈 절차가 하고, 덮이지 않은 구역이 있으면 오픈이 실패한다.
create table seat_grade_map (
    event_id      bigint not null references event (event_id) on delete cascade,
    section       text   not null,
    seat_grade_id bigint not null references seat_grade (seat_grade_id) on delete restrict,

    primary key (event_id, section),

    -- `seat.section` 과 같은 형식이다. 갈리면 매핑이 조용히 아무 구역도 안 덮는다.
    constraint seat_grade_map_section_format_check check (section ~ '^F[0-9]+-[A-Z]$')
);

-- 등급이 그 공연의 것이어야 한다. 다른 공연의 등급을 가리키면 가격이 남의 공연 값이 된다.
-- **복합 외래키로 못 건다** — `seat_grade` 의 기본키가 단일 열이라 `(event_id, seat_grade_id)` 를 가리킬 수 없다.
create or replace function seat_grade_map_same_event() returns trigger as $$
declare
    grade_event bigint;
begin
    select event_id into grade_event from seat_grade where seat_grade_id = new.seat_grade_id;

    if grade_event is distinct from new.event_id then
        raise exception '다른 공연의 등급이다: event_id=%, seat_grade_id=%', new.event_id, new.seat_grade_id;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger seat_grade_map_event_check
    before insert or update on seat_grade_map
    for each row execute function seat_grade_map_same_event();

-- 회차. 상태는 `DRAFT → OPEN → CLOSED` 한 방향이다(`D3`).
create table performance (
    performance_id bigint generated always as identity primary key,
    event_id       bigint      not null references event (event_id) on delete restrict,
    hall_id        bigint      not null references hall (hall_id) on delete restrict,

    -- 관람 일시. 취소 수수료 구간이 이 값을 기준으로 갈린다(ADR 0003).
    starts_at      timestamptz not null,

    -- 판매 시작. 대기열이 이 시각에 몰린다.
    sales_open_at  timestamptz not null,

    status         text        not null default 'draft',

    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),

    constraint performance_status_check check (status in ('draft', 'open', 'closed')),

    -- 판매를 관람 뒤에 시작할 수는 없다. 이것을 안 막으면 살 수 없는 회차가 만들어지고,
    -- 그 회차의 대기열·환불 구간이 전부 음수 시간 위에서 계산된다.
    constraint performance_sales_before_start_check check (sales_open_at < starts_at)
);

comment on column performance.starts_at is '관람 일시. 취소 수수료 구간의 기준이다(ADR 0003)';

create index performance_event_idx on performance (event_id, starts_at);
create index performance_sales_open_idx on performance (sales_open_at) where status = 'draft';

create trigger performance_set_updated_at
    before update on performance
    for each row execute function set_updated_at();

-- 상태 전이를 한 방향으로 묶는다.
--
-- **전이표를 코드에만 두면 `psql`·배치로 들어오는 변경을 못 막는다**(`D14` 축 2).
-- 되돌리기가 특히 위험하다 — `open` 에서 `draft` 로 가면 이미 복제된 `performance_seat` 와
-- 팔린 좌석이 남은 채로 「아직 안 연 회차」가 된다.
create or replace function performance_status_transition() returns trigger as $$
begin
    if old.status = new.status then
        return new;
    end if;

    if not (old.status = 'draft' and new.status = 'open'
            or old.status = 'open' and new.status = 'closed') then
        raise exception '할 수 없는 회차 상태 전이다: % -> %', old.status, new.status;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger performance_status_check_transition
    before update of status on performance
    for each row execute function performance_status_transition();
