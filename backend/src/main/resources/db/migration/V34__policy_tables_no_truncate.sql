-- 정책 표를 통째로 비우는 길을 막는다(마무리 33차 독립 리뷰 → `I15-3`).
--
-- `V32` 의 행 트리거는 `truncate` 에 안 걸린다. `refund_fee_tier` 는 들어오는 FK 가 없어 `truncate refund_fee_tier` 한 줄이
-- 구간표를 비우고, `settlement_policy` 는 `cascade` 를 붙이면 회차·정산서까지 같이 비운다. 한 줄씩 지우는 것은 막으면서
-- 통째로 비우는 것을 여는 것은 앞뒤가 안 맞는다 — `V3` 이 감사 로그에 같은 이유로 statement 트리거를 걸었다.

create or replace function policy_table_no_truncate() returns trigger as $$
begin
    raise exception '정책 표는 비우지 않는다 — 개정은 새 행이다: %', tg_table_name;
end;
$$ language plpgsql;

create trigger settlement_policy_truncate_guard
    before truncate on settlement_policy
    for each statement execute function policy_table_no_truncate();

create trigger refund_fee_tier_truncate_guard
    before truncate on refund_fee_tier
    for each statement execute function policy_table_no_truncate();
