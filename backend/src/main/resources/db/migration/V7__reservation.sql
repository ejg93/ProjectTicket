-- 예매·예매 좌석·멱등키. 선점(청크 13)이 쓰는 표 셋과, 좌석 표의 포인터 외래키.
--
-- 두 표의 역할이 다르다(D4). `performance_seat.reservation_id` 는 **지금 이 좌석을 쥔 예매**(포인터, 풀리면 null)고
-- `reservation_seat` 는 **이 예매가 잡았던 좌석과 그때 가격**(기록, 선점 때 한 번 쓰고 안 고친다).

create table reservation (
    reservation_id bigint      generated always as identity primary key,

    -- 거래 기록이라 영구다(D2). 탈퇴는 계정 행을 지우지 않고 파기하므로 restrict 가 실제로 걸릴 일은 없고, 걸리면 그것이 버그다.
    account_id     bigint      not null references account (account_id) on delete restrict,
    performance_id bigint      not null references performance (performance_id) on delete restrict,

    status         text        not null default 'held',

    -- 좌석 가격의 합. 합계는 원본과 어긋날 수 있는 유일한 종류의 값이라 아래 지연 트리거가 커밋 때 대조한다(D14 「불변식」).
    total_amount   int         not null,

    -- 선점 시점에 DB 가 계산해 박제한다(D7). 선점 시간을 나중에 바꿔도 이미 잡은 좌석의 만료가 안 흔들린다.
    held_until     timestamptz not null,
    -- `paying` 인 동안만 값이 있다(ADR 0004). 타임아웃(16)이 이것을 본다.
    paying_until   timestamptz,

    reserved_at    timestamptz,
    cancelled_at   timestamptz,
    expired_at     timestamptz,

    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),

    constraint reservation_status_check check (status in ('held', 'paying', 'reserved', 'cancelled', 'expired')),
    constraint reservation_total_amount_check check (total_amount >= 0),

    -- 상태와 시각 열이 어긋나는 것을 막는다. `reserved` 인데 `reserved_at` 이 없으면 정산(27)이 그 예매를 못 센다.
    -- `reserved` 에서 `cancelled` 로 가면 `reserved_at` 은 남는다 — 그래서 뒤 상태일수록 조건이 느슨하다.
    constraint reservation_status_fields_check check (
        status = 'held'      and paying_until is null     and reserved_at is null and cancelled_at is null and expired_at is null
     or status = 'paying'    and paying_until is not null and reserved_at is null and cancelled_at is null and expired_at is null
     or status = 'reserved'  and reserved_at is not null  and cancelled_at is null and expired_at is null
     or status = 'cancelled' and cancelled_at is not null and expired_at is null
     or status = 'expired'   and expired_at is not null   and reserved_at is null and cancelled_at is null
    )
);

comment on table reservation is '예매. 좌석 상태는 이 상태의 투영이다(D3). 영구 보존';
comment on column reservation.total_amount is '좌석 가격의 합. reservation_total_check 가 커밋 때 대조한다';

-- 내 예매 목록(44)이 계정별 최신순으로 읽는다.
create index reservation_account_idx on reservation (account_id, created_at desc);
-- 스윕(14)이 만료된 선점만 훑는다. `held` 만 들어가는 부분 인덱스다.
create index reservation_held_until_idx on reservation (held_until) where status = 'held';
-- 결제 타임아웃(16)도 같은 모양이다.
create index reservation_paying_until_idx on reservation (paying_until) where status = 'paying';
-- 회차 취소·종료(16a·17a)가 회차의 살아있는 예매를 모아 옮긴다.
create index reservation_performance_idx on reservation (performance_id, status);

create trigger reservation_set_updated_at
    before update on reservation
    for each row execute function set_updated_at();

-- 전이표(D3)에 없는 전이를 거절한다. 트리거는 문지기다 — 상태를 대신 옮기지 않는다(D3 「어긋남은 트리거가 막되」).
create or replace function reservation_status_transition() returns trigger as $$
begin
    if old.status = new.status then
        return new;
    end if;

    if not (old.status = 'held'     and new.status in ('paying', 'expired', 'cancelled')
         or old.status = 'paying'   and new.status in ('reserved', 'held', 'expired', 'cancelled')
         or old.status = 'reserved' and new.status = 'cancelled') then
        raise exception '할 수 없는 예매 상태 전이다: % -> % (reservation_id=%)', old.status, new.status, old.reservation_id;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger reservation_status_check_transition
    before update of status on reservation
    for each row execute function reservation_status_transition();

-- 예매는 반드시 `held` 로 태어난다. 전이 트리거는 update 만 보므로 삽입 자리를 따로 막는다(V5 의 회차와 같은 이유).
create or replace function reservation_starts_as_held() returns trigger as $$
begin
    if new.status <> 'held' then
        raise exception '예매는 held 로만 만들 수 있다: status=%', new.status;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger reservation_starts_held
    before insert on reservation
    for each row execute function reservation_starts_as_held();


create table reservation_seat (
    reservation_seat_id bigint      generated always as identity primary key,
    reservation_id      bigint      not null references reservation (reservation_id) on delete cascade,
    performance_seat_id bigint      not null references performance_seat (performance_seat_id) on delete restrict,

    -- 잡을 때의 가격. `performance_seat.price` 의 사본이다 — 그쪽은 박제라 같지만, 기록은 기록 안에서 닫혀야 한다.
    price               int         not null,

    created_at          timestamptz not null default now(),

    constraint reservation_seat_key unique (reservation_id, performance_seat_id),
    constraint reservation_seat_price_check check (price >= 0)
);

comment on table reservation_seat is '이 예매가 잡은 좌석과 그때 가격. 선점 때 한 번 쓰고 안 고친다(D4)';

-- 발권(18)·정산(27)이 좌석 쪽에서 예매를 찾는다. `restrict` 참조라 없으면 좌석 삭제마다 이 표를 훑는다.
create index reservation_seat_performance_seat_idx on reservation_seat (performance_seat_id);

-- 기록이라 안 고친다. 고칠 자리가 없어야 「그때 가격」이 그때 가격이다.
create or replace function reservation_seat_append_only() returns trigger as $$
begin
    raise exception '예매 좌석 기록은 고칠 수 없다: reservation_seat_id=%', old.reservation_seat_id;
end;
$$ language plpgsql;

create trigger reservation_seat_no_update
    before update on reservation_seat
    for each row execute function reservation_seat_append_only();


-- 포인터 외래키. 좌석이 가리키는 예매가 있어야 한다. 인덱스는 V6 의 `performance_seat_reservation_idx` 가 이미 있다.
alter table performance_seat
    add constraint performance_seat_reservation_id_fkey
    foreign key (reservation_id) references reservation (reservation_id) on delete restrict;


-- 합계 불변식(D4): reservation.total_amount = Σ reservation_seat.price. 살아있는 예매는 좌석이 하나 이상이다.
--
-- 지연 제약 트리거다. 선점 순서가 「예매 행 → 좌석 기록」이라 즉시 트리거는 예매 행이 들어오는 순간 0 과 대조하게 된다.
-- **NEW 를 안 믿고 행을 다시 읽는다.** NEW 는 트리거를 걸어 준 문장 시점의 값이라, 뒤 문장이 고쳐도 커밋 때 옛 값을 본다(stack.md).
create or replace function reservation_total_matches_seats() returns trigger as $$
declare
    v_reservation_id bigint := coalesce(new.reservation_id, old.reservation_id);
    v_status         text;
    v_total          int;
    v_sum            int;
    v_count          int;
begin
    select status, total_amount into v_status, v_total
      from reservation where reservation_id = v_reservation_id;

    -- 같은 트랜잭션에서 지워졌다. 검사할 것이 없다.
    if not found then
        return null;
    end if;

    select coalesce(sum(price), 0), count(*) into v_sum, v_count
      from reservation_seat where reservation_id = v_reservation_id;

    if v_total <> v_sum then
        raise exception '예매 합계가 좌석 가격의 합과 다르다: reservation_id=%, total_amount=%, sum=%', v_reservation_id, v_total, v_sum;
    end if;

    if v_status in ('held', 'paying', 'reserved') and v_count = 0 then
        raise exception '살아있는 예매에 좌석이 없다: reservation_id=%', v_reservation_id;
    end if;

    return null;
end;
$$ language plpgsql;

create constraint trigger reservation_total_check
    after insert or update on reservation
    deferrable initially deferred
    for each row execute function reservation_total_matches_seats();

create constraint trigger reservation_seat_total_check
    after insert or update or delete on reservation_seat
    deferrable initially deferred
    for each row execute function reservation_total_matches_seats();


-- 멱등키(D4). 같은 요청이 두 번 도착해도 예매가 하나만 생기게 한다. 키는 클라이언트가 만든다.
create table idempotency_key (
    idempotency_key_id bigint      generated always as identity primary key,

    -- 계정별로 유일하다. 남의 키와 겹쳐도 상관없다 — 키는 요청을 가르는 값이지 식별자가 아니다(D9).
    -- 24시간짜리 행이라 계정을 따라 사라져도 된다.
    account_id         bigint      not null references account (account_id) on delete cascade,
    key_value          text        not null,

    -- 요청 본문의 SHA-256. 본문 전체를 보관하지 않는다. 같은 키로 다른 본문이 오면 422 다.
    request_hash       text        not null,

    -- 성공 응답. 재전송이 이것을 그대로 받는다. 실패는 안 담는다 — 처리와 기록이 한 트랜잭션이라 실패하면 이 행도 롤백된다.
    response_body      jsonb,

    created_at         timestamptz not null default now(),

    constraint idempotency_key_key unique (account_id, key_value),
    -- 식별자 규약이 최대 255자로 정했다. 빈 키는 헤더를 안 보낸 것과 같다.
    constraint idempotency_key_length_check check (length(key_value) between 1 and 255),
    constraint idempotency_key_hash_check check (length(request_hash) = 64)
);

comment on table idempotency_key is '멱등키와 저장된 응답. 24시간 뒤 지운다(D4)';

-- 파기가 훑는다.
create index idempotency_key_created_idx on idempotency_key (created_at);

-- 응답이 안 채워진 채로 커밋되면 재전송이 빈 답을 받는다. 선점(insert)과 응답 저장(update)이 한 트랜잭션이라
-- 커밋 시점에는 반드시 채워져 있어야 하고, 안 채워졌으면 저장하는 코드를 빠뜨린 것이다.
-- check 는 지연이 안 돼서 트리거다. NEW 를 안 믿는 이유는 위 합계 트리거와 같다.
create or replace function idempotency_key_has_response() returns trigger as $$
declare
    v_body jsonb;
begin
    select response_body into v_body from idempotency_key where idempotency_key_id = new.idempotency_key_id;

    if not found then
        return null;
    end if;

    if v_body is null then
        raise exception '멱등키에 응답이 안 붙었다: key=%', new.key_value;
    end if;

    return null;
end;
$$ language plpgsql;

create constraint trigger idempotency_key_response_check
    after insert or update on idempotency_key
    deferrable initially deferred
    for each row execute function idempotency_key_has_response();
