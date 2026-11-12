-- 정산 금액을 64비트로 넓힌다(점검 16차 → `I16-2`).
--
-- `settlement.amount`·`settlement_line.amount` 가 `int`(약 21.47억)라 회차 매출이 그 위로 가면 집계가 깨진다.
-- 가격 상한은 1천만 원(`V27`)이고 홀 좌석 수 상한은 없다 — 15,000석 × 15만 원이 22.5억이다.
-- 예매·결제·환불 금액은 4석 × 1천만 원이라 `int` 그대로 둔다(`V27` 주석).
--
-- 두 열을 `update of`·`when` 으로 무는 트리거는 없다(`V25`·`V32` 는 `status`·`settlement_policy_id`). 합계 트리거는
-- 변수가 `int` 라 같이 넓힌다 — 본문은 `V16` 그대로고 형만 바뀐다.

alter table settlement alter column amount type bigint;
alter table settlement_line alter column amount type bigint;

-- 합계 불변식(D21): settlement.amount = Σ settlement_line.amount.
-- **지연이다** — 항목이 정산서보다 늦게 들어온다. `scheduled` 정산서는 항목이 없고 금액이 0 이라 0 = 0 으로 통과한다.
-- NEW 를 안 믿고 행을 다시 읽는다(V7·V10 과 같은 이유 — stack.md).
create or replace function settlement_total_matches_lines() returns trigger as $$
declare
    v_settlement_id bigint := coalesce(new.settlement_id, old.settlement_id);
    v_amount        bigint;
    v_sum           bigint;
begin
    select amount into v_amount from settlement where settlement_id = v_settlement_id;

    if not found then
        return null;
    end if;

    select coalesce(sum(amount), 0) into v_sum from settlement_line where settlement_id = v_settlement_id;

    if v_amount <> v_sum then
        raise exception '정산서 금액이 항목의 합과 다르다: settlement_id=%, amount=%, sum=%', v_settlement_id, v_amount, v_sum;
    end if;

    return null;
end;
$$ language plpgsql;
