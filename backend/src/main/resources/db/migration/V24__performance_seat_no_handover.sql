-- 좌석은 예매를 바꿔 가리키지 않는다(청크 51). 초과 판매를 막는 셋째 겹이다.
--
-- 앞의 둘은 앱에 있다 — 선점의 조건부 UPDATE(`status = 'available'`)와 갱신 행 수 대조(`SeatHoldService`).
-- 둘 중 하나가 빠지면 진 쪽 선점이 이미 잡힌 좌석의 `reservation_id` 를 A 에서 B 로 덮어쓴다. DB 는 그것을 못 봤다 —
-- `performance_seat_held_fields_check`(V6)는 새 행의 칸 짝만 본다.
--
-- **check 로 못 건다.** 막을 것은 「값」이 아니라 「값의 바뀜」이라 old 를 봐야 한다(D14 「불변식 — 어디에 거나」).
-- 지연도 아니다 — 갱신하는 순간 old·new 가 다 있어서 그 자리에서 판정할 수 있다.
--
-- 정상 경로에 A→B 가 없다(2026-10-18, `update performance_seat` 일곱 자리 전부): 선점은 null→A, 스윕·취소·환불·회차 종료·확정 해제는 A→null,
-- 확정은 `reservation_id` 를 그대로 둔다. 그래서 조건이 「둘 다 null 이 아니고 다르다」 하나다. 이 트리거에 걸리는 경로가 생기면 경로가 틀린 것이다.
create or replace function performance_seat_keeps_reservation() returns trigger as $$
begin
    raise exception '좌석이 다른 예매로 넘어간다: performance_seat_id=%, from=%, to=%',
        old.performance_seat_id, old.reservation_id, new.reservation_id;
end;
$$ language plpgsql;

create trigger performance_seat_no_handover
    before update on performance_seat
    for each row
    when (old.reservation_id is not null and new.reservation_id is not null and new.reservation_id <> old.reservation_id)
    execute function performance_seat_keeps_reservation();
