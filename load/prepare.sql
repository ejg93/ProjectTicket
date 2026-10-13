-- 부하 시험용 계정(31·35b). 1만 개 — 대기열 ZSET 의 원소가 계정이라 계정 수가 줄 길이의 상한이다(35c).
--
-- **비밀번호 해시는 bcrypt 비용 4 리터럴이다**(= `demo-password-1234`). 재는 것은 좌석 경합·대기열이지 bcrypt 가 아니다 —
-- 비용 10(운영, `SecurityConfig`)이면 1만 번 로그인에 CPU 가 수백 초 든다. `$2b` 는 Spring 이 `$2a` 와 같이 받는다(설계 실측).
-- 2차(`load-2`)까지는 데모 계정의 비용 10 해시를 베꼈다 — 로그인이 섞인 숫자를 비교할 때 그 차이를 같이 적는다.
insert into account (email, password_hash, display_name, role)
select
    'load' || g || '@test.local',
    '{bcrypt}$2b$04$UMq/AYQzOga3nBY6Ibg2zeiajKTP/kgUU7Pld3MyTI9biXL.OcEOW',
    'load' || g,
    'audience'
from generate_series(1, 10000) g
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
