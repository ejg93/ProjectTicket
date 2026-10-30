-- 회차 종료와 정산 집계의 시각 조건을 DB 가 막는다(I13-7c). 전이표는 `state-machines.md` 「회차」·「정산」.
--
-- 지금까지 두 트리거(V17·V25)는 from→to 만 봤다. 시각 조건은 앱 SQL 에만 있었다 —
-- `open→closed` 의 `sales_close_at < now()` 는 `PerformanceCloseService`, `scheduled→pending` 의 `settle_at <= now()` 는
-- `SettlementStore.takeDue`. `psql`·배치·새 입구는 그것을 안 지난다(`D14` 축 2).
--
-- `open→cancelled` 는 시각과 무관하다 — 기획사가 언제든 취소한다.
-- 허용 전이 블록은 V17·V25 와 글자가 같다 — `StateMachineDocTest` 가 그 꼴을 읽는다. 조건 검사는 `new.status` 를 앞에 써서 그 꼴에 안 걸린다.
-- 함수 둘을 한 파일에 둔다 — 마이그레이션은 청크당 하나다.
create or replace function performance_status_transition() returns trigger as $$
begin
    if old.status = new.status then
        return new;
    end if;

    if not (old.status = 'draft' and new.status = 'open'
            or old.status = 'open' and new.status in ('closed', 'cancelled')) then
        raise exception '할 수 없는 회차 상태 전이다: % -> %', old.status, new.status;
    end if;

    if new.status = 'closed' and old.sales_close_at >= now() then
        raise exception '판매 마감 전에 닫을 수 없다: sales_close_at=% (performance_id=%)', old.sales_close_at, old.performance_id;
    end if;

    return new;
end;
$$ language plpgsql;

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

    if new.status = 'pending' and old.settle_at > now() then
        raise exception '정산일 전에 집계할 수 없다: settle_at=% (settlement_id=%)', old.settle_at, old.settlement_id;
    end if;

    return new;
end;
$$ language plpgsql;
