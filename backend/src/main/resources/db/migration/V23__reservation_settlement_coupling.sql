-- 예매·정산이 다른 열에 기대는 등식 둘과 노는 인덱스 하나(점검 7차 `I7` → `I7-1`).

-- 「무엇이 물렀나」는 끝난 예매에만 있다.
--
-- `V19` 가 열을 더하면서 전이 트리거로 「끝날 때 반드시 적는다」만 걸었다. 반대쪽 — **살아 있는 예매에 값이 있는 것** — 은
-- 아무것도 안 막았다. 시각 다섯을 상태에 묶는 `reservation_status_fields_check`(`V7`)와 같은 자리인데 머지된 `V7` 은 못 고쳐서
-- 따로 건다. 지금은 알림 수신자 질의가 `status` 와 `cancelled_by` 를 같이 봐서 안 틀리지만, `cancelled_by` 하나만 보는
-- 질의가 생기는 날 살아 있는 예매를 「관객이 무른 것」으로 센다(정산 `27`·내 예매 `D21` 이 같은 질문을 한다).
--
-- `expired` 의 사유는 `expired` 하나뿐이라 그것까지 묶을 수 있지만, 상태↔사유 짝은 전이 트리거가 이미 요구하고
-- 여기서는 「있다/없다」만 상태에 묶는다 — 시각 check 와 같은 모양으로.
alter table reservation add constraint reservation_cancelled_by_status_check
    check ((cancelled_by is null) = (status not in ('cancelled', 'expired')));

comment on constraint reservation_cancelled_by_status_check on reservation is
    'cancelled_by 는 cancelled·expired 에만 있고 거기에는 반드시 있다 — V19 의 전이 트리거가 반쪽(끝날 때)만 걸었다';


-- 정산서의 정책은 회차가 오픈 때 박제한 정책이다(`D21` 「박제」).
--
-- `SettlementStore.schedule` 이 `performance.settlement_policy_id` 에서 베껴 넣어서 지금은 안 갈린다. 베끼는 입구가
-- 하나 더 생기는 날(수기 정산·재정산) 다른 정책을 넣으면 `takeDue` 가 **남의 요율로 수수료를 센다** — 오류 없이 돈이 틀린다.
-- check 로 못 건다 — 다른 표의 행을 본다. 넣는 순간 회차 행이 이미 있어서 그 자리에서 판정한다(`V12` 와 같은 이유).
create or replace function settlement_policy_matches_performance() returns trigger as $$
declare
    v_policy bigint;
begin
    select settlement_policy_id into v_policy from performance where performance_id = new.performance_id;

    if v_policy is null or v_policy <> new.settlement_policy_id then
        raise exception '정산서의 정책이 회차의 박제와 다르다: performance_id=%, settlement_policy_id=%, 회차=%',
            new.performance_id, new.settlement_policy_id, v_policy;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger settlement_policy_matches_performance_check
    before insert on settlement
    for each row execute function settlement_policy_matches_performance();


-- 내 알림 화면을 전제로 `V15` 가 만든 인덱스. 그 화면이 계획에 없다 — `44a`~`44c` 가 전부 예매 화면이고
-- 이 인덱스를 읽는 질의가 0 이다. 쓰기마다 값만 치른다(사용자 결정 2026-10-11 (가)). 계정별로 알림을 읽는 질의를 더하는 청크가 인덱스를 같이 만든다 — 지금 `PLAN.md` 에 그 화면이 없다.
-- `V15` 의 주석은 머지돼서 못 고친다 — 이 줄이 그 주석을 무른다.
drop index notification_account_idx;
