-- 회차별 좌석 상태. **좌석 상태의 단일 진실이다**(`D2`).
--
-- 왜 복제하나: 같은 홀을 회차 둘이 쓰면 한 좌석에 상태가 둘 필요해진다. `seat` 에 상태를 두면 그 둘이 부딪친다.
-- 등급·가격도 여기 박제한다 — 공연이 가격을 고쳐도 이미 열린 회차의 값은 안 바뀐다.
--
-- 복제 비용은 회차당 좌석 수(수천 행)고 오픈 한 번에 든다.

create table performance_seat (
    performance_seat_id bigint generated always as identity primary key,

    performance_id      bigint      not null references performance (performance_id) on delete cascade,

    -- 어느 물리 좌석인가. `seat` 는 수정·삭제되지 않으므로(`V4`) 이 참조가 나중에 다른 자리를 가리키지 않는다.
    seat_id             bigint      not null references seat (seat_id) on delete restrict,

    -- 오픈 시점의 등급과 가격. 박제다.
    seat_grade_id       bigint      not null references seat_grade (seat_grade_id) on delete restrict,
    price               int         not null,

    status              text        not null default 'available',

    -- `held` 인 동안만 값이 있다. 이 시각이 지나면 스윕이 되돌린다(청크 14).
    held_until          timestamptz,

    -- 이 좌석을 잡은 예매. 예매 테이블은 청크 13 이 만들고 그때 외래키를 건다.
    reservation_id      bigint,

    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),

    -- 한 회차에서 좌석 하나는 한 행이다. **이것이 복제를 두 번 하는 사고를 막는다** —
    -- 오픈을 두 번 부르면 여기서 걸린다.
    constraint performance_seat_key unique (performance_id, seat_id),

    constraint performance_seat_status_check check (status in ('available', 'held', 'reserved')),
    constraint performance_seat_price_check check (price >= 0),

    -- **상태와 부속 값이 어긋나는 것을 막는다.** `held` 인데 만료 시각이 없으면 스윕이 그 좌석을 영영 못 되돌리고,
    -- `available` 인데 예매를 가리키면 같은 좌석이 두 번 팔린 것처럼 보인다.
    constraint performance_seat_held_fields_check check (
        status = 'available' and held_until is null and reservation_id is null
        or status = 'held' and held_until is not null and reservation_id is not null
        or status = 'reserved' and held_until is null and reservation_id is not null
    )
);

comment on table performance_seat is '회차별 좌석 상태. 좌석 상태의 단일 진실이다(D2). Redis 는 이 값을 정하지 않는다';
comment on column performance_seat.price is '오픈 시점의 가격. 공연이 값을 고쳐도 열린 회차는 안 바뀐다';

-- 좌석 현황 조회(청크 10)가 회차 하나를 구역·열 순으로 통째로 읽는다.
create index performance_seat_performance_idx on performance_seat (performance_id);

-- 스윕(청크 14)이 만료된 선점만 훑는다. 부분 인덱스라 `available`·`reserved` 행은 안 들어간다.
create index performance_seat_held_until_idx on performance_seat (held_until) where status = 'held';

create trigger performance_seat_set_updated_at
    before update on performance_seat
    for each row execute function set_updated_at();
