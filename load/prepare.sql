-- 부하 시험용 계정(31). 비밀번호 해시는 **데모 계정의 것을 그대로 쓴다** —
-- bcrypt 를 1천 번 계산하는 데 몇 분이 걸리고, 여기서 재려는 것은 로그인 비용이 아니라 좌석 경합이다.
--
-- 데모 시드(`local` 프로필)가 먼저 돌아 있어야 한다. 없으면 이 문장이 0행을 넣고, k6 가 로그인에서 전부 401 이다.
insert into account (email, password_hash, display_name, role)
select
    'load' || g || '@test.local',
    (select password_hash from account where email = 'audience1@demo.local'),
    'load' || g,
    'audience'
from generate_series(1, 300) g
where exists (select 1 from account where email = 'audience1@demo.local')
on conflict do nothing;

-- 회차와 좌석 번호의 시작값. k6 의 `PERFORMANCE_ID`·`FIRST_SEAT_ID`·`SEATS` 에 이 값을 넣는다.
select
    p.performance_id,
    min(ps.performance_seat_id) as first_seat_id,
    count(*)                    as seats
from performance p
join performance_seat ps on ps.performance_id = p.performance_id
where p.status = 'open'
group by p.performance_id
order by p.performance_id;
