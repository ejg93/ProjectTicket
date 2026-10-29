-- 환불·정산 전이 트리거(점검 11차 `I11-6`, D3). 전이표는 코드에 선언하고 DB 트리거가 되돌리기를 막는다(`state-machines.md`).
--
-- 지금까지는 `reservation`·`performance` 에만 걸렸다. 환불은 `done → requested`, 정산은 `pending → scheduled` 같은
-- 되돌리기를 막는 것이 없었다 — 되돌린 정산서는 스케줄러가 한 번 더 집계한다(`SettlementStore` 의 조건부 UPDATE 가 `scheduled` 만 본다).

create or replace function refund_status_transition() returns trigger as $$
begin
    if old.status = new.status then
        return new;
    end if;

    if not (old.status = 'requested' and new.status = 'done') then
        raise exception '할 수 없는 환불 상태 전이다: % -> % (refund_id=%)', old.status, new.status, old.refund_id;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger refund_status_check_transition
    before update of status on refund
    for each row execute function refund_status_transition();


-- `pending → confirmed → paid` 는 아직 부르는 코드가 없다(D21 「누가 보나」 — 관리자 화면이 설 때). 전이표는 D21 의 순서를 먼저 세운다.
create or replace function settlement_status_transition() returns trigger as $$
begin
    if old.status = new.status then
        return new;
    end if;

    if not (old.status = 'scheduled' and new.status = 'pending'
            or old.status = 'pending' and new.status = 'confirmed'
            or old.status = 'confirmed' and new.status = 'paid') then
        raise exception '할 수 없는 정산 상태 전이다: % -> % (settlement_id=%)', old.status, new.status, old.settlement_id;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger settlement_status_check_transition
    before update of status on settlement
    for each row execute function settlement_status_transition();
