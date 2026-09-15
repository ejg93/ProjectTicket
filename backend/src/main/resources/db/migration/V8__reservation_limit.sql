-- 같은 계정이 같은 회차에 살아있는 선점을 둘 못 든다(청크 15, ADR 0003 「1인 최대 매수」).
--
-- 부분 유일 인덱스다(D14 「불변식」 — 조건이 붙은 유일성). `reserved` 는 뺀다 — 확정한 뒤에 같은 회차를 더 사는 것은 된다(회차당 4매 안에서).
--
-- **이 인덱스가 회차당 매수 검사를 경합에서 지킨다.** 같은 계정의 두 선점이 동시에 오면 둘째 insert 가 첫째의 커밋을 기다리고,
-- 그 사이 첫째가 센 「이미 잡은 매수」는 다른 트랜잭션이 못 바꾼다 — 살아있는 선점이 이 계정에 하나뿐이라서다.
-- 앱 검증(SeatHoldService)이 3위 강제 지점인데도 경합에 안전한 이유가 이것이다.
create unique index reservation_live_hold_idx
    on reservation (account_id, performance_id)
    where status in ('held', 'paying');

comment on index reservation_live_hold_idx is '계정·회차당 살아있는 선점 하나. 회차당 매수 검사(SeatHoldService)를 경합에서 지킨다';
