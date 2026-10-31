-- 예매 전이의 시각·결제 조건과 발권 조건을 DB 가 막는다(I13-7a). 전이표는 `state-machines.md` 「예매」.
--
-- 지금까지 트리거(V19)는 from→to 와 `cancelled_by` 만 봤다. 시각 조건은 `PaymentTransitionService` 의 조건부 UPDATE 에만 있었고,
-- 「`reserved` 예매에는 승인 결제가 하나 있다」는 `RefundTransitionService` 가 환불 때 터뜨리는 예외뿐이었다.
-- `psql`·배치·새 입구는 그것을 안 지난다(`D14` 축 2).
--
-- 시각 조건은 **앞으로 가는 전이 셋**에만 건다 — held→paying · paying→reserved · paying→held.
-- 만료·취소는 시각과 무관하다. 회차 취소·종료가 `held`·`paying` 을 시각과 상관없이 옮긴다.
-- `now()` 는 트랜잭션 시작 시각이라 앱 SQL 의 `now()` 와 같은 시계다.
--
-- 허용 전이 블록은 V19 와 글자가 같다 — `StateMachineDocTest` 가 그 꼴을 읽는다. 조건 검사는 `new.status` 를 앞에 써서 그 꼴에 안 걸린다.
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

    if new.status in ('cancelled', 'expired') and new.cancelled_by is null then
        raise exception '무엇이 물렀는지 없이 % 로 갈 수 없다 (reservation_id=%)', new.status, old.reservation_id;
    end if;

    if new.status = 'paying' and old.held_until < now() then
        raise exception '선점 시각이 지났다: held_until=% (reservation_id=%)', old.held_until, old.reservation_id;
    end if;

    if new.status = 'held' and old.status = 'paying' and old.held_until < now() then
        raise exception '선점 시각이 지났다: held_until=% (reservation_id=%)', old.held_until, old.reservation_id;
    end if;

    if new.status = 'reserved' and old.paying_until < now() then
        raise exception '결제 시각이 지났다: paying_until=% (reservation_id=%)', old.paying_until, old.reservation_id;
    end if;

    -- 결제 행은 확정보다 먼저 들어간다(`PaymentTransitionService.settle` — insertPayment → confirm → issue).
    if new.status = 'reserved' and not exists (
        select 1 from payment where reservation_id = new.reservation_id and status = 'approved'
    ) then
        raise exception '승인 결제 없이 확정할 수 없다 (reservation_id=%)', old.reservation_id;
    end if;

    return new;
end;
$$ language plpgsql;

-- 발권은 확정 예매의 좌석에만. `TicketService.issue` 는 예매 상태를 안 본다 — 부르는 곳이 확정 뒤 하나뿐이라서다.
create or replace function ticket_requires_reserved() returns trigger as $$
declare
    v_status text;
begin
    select r.status into v_status
      from reservation_seat rs
      join reservation r on r.reservation_id = rs.reservation_id
     where rs.reservation_seat_id = new.reservation_seat_id;

    if v_status is distinct from 'reserved' then
        raise exception '확정 예매에만 발권한다: status=% (reservation_seat_id=%)', v_status, new.reservation_seat_id;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger ticket_requires_reserved
    before insert on ticket
    for each row execute function ticket_requires_reserved();
