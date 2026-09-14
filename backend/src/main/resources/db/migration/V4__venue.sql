-- 기획사와 공연장. 공연(청크 8)이 이 둘을 가리킨다.
--
-- **좌석은 여기서 끝난다.** 상태는 회차가 든다(`performance_seat`, 청크 9) — 같은 홀을 회차 둘이 쓰면
-- 한 좌석에 상태가 둘 필요해지기 때문이다(`D2`).

-- ─────────────────────────────────────────────────────────────
-- 기획사
-- ─────────────────────────────────────────────────────────────
create table organizer (
    organizer_id bigint generated always as identity primary key,

    -- 코드에서 참조하는 안정된 키. 이름은 바뀌어도 이것은 안 바뀐다.
    code         text        not null unique,
    name         text        not null,

    -- 정지된 기획사는 공연을 못 올린다. 계정과 같은 축이다(`account.status`).
    status       text        not null default 'active',

    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),

    constraint organizer_status_check check (status in ('active', 'suspended')),
    constraint organizer_code_format_check check (code ~ '^[a-z0-9][a-z0-9-]{1,30}$')
);

create trigger organizer_set_updated_at
    before update on organizer
    for each row execute function set_updated_at();

-- 계정 ↔ 기획사 소속.
--
-- **`account.role` 과 축이 다르다.** 역할은 「기획사 쪽 사람인가」를 답하고 이 표는 「어느 기획사인가」를 답한다.
-- 한 컬럼에 섞으면 역할 목록이 기획사 수만큼 늘어난다(ADR 0002).
--
-- 한 사람이 기획사 여럿에 속할 수 있다. 막을 이유가 없고, 막으면 대행사가 여러 기획사를 맡는 흔한 모양이 안 된다.
create table organizer_member (
    organizer_id bigint      not null references organizer (organizer_id) on delete cascade,
    account_id   bigint      not null references account (account_id) on delete cascade,
    created_at   timestamptz not null default now(),

    primary key (organizer_id, account_id)
);

-- 「이 계정이 어느 기획사에 속하나」가 권한 판정의 입력이다(청크 11).
create index organizer_member_account_idx on organizer_member (account_id);

-- 기획사 역할이 아닌 계정이 소속되는 것을 막는다.
--
-- **앱 검증으로는 못 막는다**(`D14` 축 2). 소속을 넣는 입구가 늘 때마다 검사를 빠뜨릴 수 있고,
-- 빠뜨리면 「기획사 화면이 보이는데 권한은 없는 계정」이 조용히 생긴다.
--
-- 역할을 관객으로 되돌리는 update 도 같이 막아야 해서 `account` 쪽에도 건다 —
-- 소속만 보면 이미 들어온 행이 뒤늦게 어긋나는 것을 못 본다.
create or replace function organizer_member_requires_role() returns trigger as $$
declare
    member_role text;
begin
    select role into member_role from account where account_id = new.account_id;

    if member_role not in ('organizer', 'admin') then
        raise exception '기획사에 소속되려면 역할이 organizer 여야 한다: account_id=%, role=%',
            new.account_id, member_role;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger organizer_member_role_check
    before insert or update on organizer_member
    for each row execute function organizer_member_requires_role();

create or replace function account_role_change_keeps_membership() returns trigger as $$
begin
    if new.role not in ('organizer', 'admin')
       and exists (select 1 from organizer_member where account_id = new.account_id) then
        raise exception '기획사에 소속된 계정의 역할을 내릴 수 없다: account_id=%', new.account_id;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger account_role_keeps_membership
    before update of role on account
    for each row execute function account_role_change_keeps_membership();

-- ─────────────────────────────────────────────────────────────
-- 공연장과 홀
-- ─────────────────────────────────────────────────────────────
create table venue (
    venue_id   bigint generated always as identity primary key,
    name       text        not null,
    address    text        not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create trigger venue_set_updated_at
    before update on venue
    for each row execute function set_updated_at();

-- 홀. 좌석 배치의 단위다.
create table hall (
    hall_id    bigint generated always as identity primary key,
    venue_id   bigint      not null references venue (venue_id) on delete cascade,
    name       text        not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint hall_venue_name_key unique (venue_id, name)
);

create trigger hall_set_updated_at
    before update on hall
    for each row execute function set_updated_at();

-- ─────────────────────────────────────────────────────────────
-- 좌석
-- ─────────────────────────────────────────────────────────────
--
-- **수정도 삭제도 안 한다.** 회차가 오픈될 때 이 행을 복제해서 `performance_seat` 를 만드는데(청크 9),
-- 배치가 바뀌면 이미 오픈된 회차의 복제본이 무엇을 가리켰는지 알 수 없게 된다.
-- 배치를 바꾸려면 홀을 새로 만든다 — 홀이 바뀐 것이 사실이고, 지난 회차는 지난 홀을 그대로 가리킨다.
create table seat (
    seat_id     bigint generated always as identity primary key,
    hall_id     bigint      not null references hall (hall_id) on delete cascade,

    -- 구역 코드. 등급이 이 단위로 붙는다(ADR 0003) — 좌석 단위로 붙이면 매핑 행이 좌석 수만큼 생긴다.
    section     text        not null,

    -- 열. 숫자가 아니라 글자다 — `A`·`B` 로 매기는 공연장이 많고, 숫자로 굳히면 그 홀을 못 담는다.
    row_label   text        not null,

    seat_number int         not null,

    created_at  timestamptz not null default now(),

    -- 홀 안에서 자리 하나를 가리키는 키. 같은 자리가 둘이면 회차 복제가 두 배로 늘어난다.
    constraint seat_position_key unique (hall_id, section, row_label, seat_number),

    -- 구역 코드 형식. `F1-A` 꼴이다(ADR 0003). 화면에 보이는 이름은 별도로 든다(청크 8).
    constraint seat_section_format_check check (section ~ '^F[0-9]+-[A-Z]$'),
    constraint seat_row_label_format_check check (row_label ~ '^[A-Z]{1,2}$'),
    constraint seat_number_positive_check check (seat_number between 1 and 999)
);

comment on table seat is '홀의 물리 좌석. 수정·삭제하지 않는다 — 회차가 이 행을 복제해서 상태를 든다';
comment on column seat.section is '구역 코드. 등급이 이 단위로 붙는다(ADR 0003)';

-- 홀 하나의 좌석을 통째로 읽는 것이 회차 오픈의 첫 단계다(청크 9).
create index seat_hall_idx on seat (hall_id);
