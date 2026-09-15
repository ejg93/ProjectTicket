-- 회차 취소(청크 17a, D3). 기획사가 `open` 회차를 되돌린다 — 판 것까지 무른다.
--
-- **`closed` 뒤의 취소는 없다**(D3). 회차가 끝난 뒤에는 정산이 이미 그 상태를 최종으로 보기 때문이고(D21),
-- 그래서 전이는 `open → cancelled` 하나다.

alter table performance drop constraint performance_status_check;
alter table performance add constraint performance_status_check check (status in ('draft', 'open', 'closed', 'cancelled'));

create or replace function performance_status_transition() returns trigger as $$
begin
    if old.status = new.status then
        return new;
    end if;

    if not (old.status = 'draft' and new.status = 'open'
            or old.status = 'open' and new.status in ('closed', 'cancelled')) then
        raise exception '할 수 없는 회차 상태 전이다: % -> %', old.status, new.status;
    end if;

    return new;
end;
$$ language plpgsql;

-- 취소된 회차는 정산서가 서지만 항목이 전부 0 이다(D21 「회차 취소」) — 「이 회차는 취소돼서 0 이다」가 기록으로 남는다.
-- 그래서 `performance_open_has_policy_check`(V16)의 대상에 `cancelled` 도 들어간다. 이미 `status = 'draft'` 만 예외라 고칠 것이 없다.
