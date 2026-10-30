-- 좌석 상태 전이를 DB 가 막는다(I13-2). 전이표는 `state-machines.md` 「좌석」이고 `StateMachineDocTest` 가 이 함수와 대조한다.
--
-- 지금까지 DB 는 칸 짝(`performance_seat_held_fields_check`, V6)과 포인터 A→B(`performance_seat_no_handover`, V24)만 봤다.
-- 그래서 `available → reserved`(선점 없이 팔림)·`reserved → held`(팔린 좌석이 선점으로 되돌아감)를 받았다.
-- 막는 것은 앱의 조건부 UPDATE 하나뿐이었다 — `psql`·배치·새 입구는 그것을 안 지난다(`D14` 축 2).
--
-- 정상 경로는 넷이다(2026-10-30, `update performance_seat` 일곱 자리): 선점 available→held · 확정 held→reserved ·
-- 만료·결제 실패·회차 종료·회차 취소 held→available · 관객 취소·회차 취소 reserved→available.
-- 같은 상태로의 갱신은 통과한다 — 포인터가 바뀌는 것은 V24 몫이다.
create or replace function performance_seat_status_transition() returns trigger as $$
begin
    if old.status = new.status then
        return new;
    end if;

    if not (old.status = 'available' and new.status = 'held'
            or old.status = 'held' and new.status in ('available', 'reserved')
            or old.status = 'reserved' and new.status = 'available') then
        raise exception '할 수 없는 좌석 상태 전이다: % -> %', old.status, new.status;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger performance_seat_status_transition
    before update on performance_seat
    for each row
    execute function performance_seat_status_transition();
