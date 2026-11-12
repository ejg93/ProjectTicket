-- 정책 행과 박제 열을 굳힌다(점검 15차 `I15` → `I15-1`).
--
-- 「개정하면 새 행을 넣고 옛 행은 남긴다」(`D21`)·「`effective_at` 으로 개정 이력」(`D6`)의 강제 지점이 `V16` 주석뿐이었다.
-- 앱에 두 표의 UPDATE/DELETE 는 없지만 `psql` 이 `commission_rate` 를 한 줄 고치면 `takeDue` 가 그 요율로 센다 — 오류 없이 돈이 틀린다.
-- 박제 열도 같다 — 회차·정산서의 정책, 환불의 율·일수·금액, 회차 좌석의 가격. 앱이 쓰는 갱신(`open` 의 null → id,
-- 환불의 `status`·`refund_number`·`done_at`, 좌석의 상태 열)은 안 걸린다.
--
-- `settlement.settle_at` 은 **안 굳힌다.** `SettlementAggregateTest` 가 `D8` 「시계가 DB 라 기다릴 방법이 없다」대로 과거로 당겨
-- 집계를 재운다. 굳히면 그 시험이 정책·`starts_at` 을 다시 짜야 하는데 얻는 것이 없다(`I11` 이 「기계가 안 재는 것」으로 적은 자리).

create or replace function policy_row_is_frozen() returns trigger as $$
begin
    raise exception '정책 행은 고치거나 지우지 않는다 — 개정은 새 행이다: %', tg_table_name;
end;
$$ language plpgsql;

create trigger settlement_policy_frozen
    before update or delete on settlement_policy
    for each row execute function policy_row_is_frozen();

create trigger refund_fee_tier_frozen
    before update or delete on refund_fee_tier
    for each row execute function policy_row_is_frozen();


-- 회차의 박제는 draft 의 null 에서 한 번 들어간다(`PerformanceOpenService`). 그 뒤 바꾸면 이미 판 표의 정산이 다른 요율을 본다.
create or replace function performance_policy_stamp_is_frozen() returns trigger as $$
begin
    raise exception '회차의 정산 정책 박제는 고칠 수 없다: performance_id=%', old.performance_id;
end;
$$ language plpgsql;

create trigger performance_policy_stamp_frozen
    before update of settlement_policy_id on performance
    for each row
    when (old.settlement_policy_id is not null and new.settlement_policy_id is distinct from old.settlement_policy_id)
    execute function performance_policy_stamp_is_frozen();


-- 정산서의 정책은 넣을 때 회차와 대조한다(`V23`). 넣은 뒤 바꾸는 길은 그 대조가 안 본다.
create or replace function settlement_policy_stamp_is_frozen() returns trigger as $$
begin
    raise exception '정산서의 정책은 고칠 수 없다: settlement_id=%', old.settlement_id;
end;
$$ language plpgsql;

create trigger settlement_policy_stamp_frozen
    before update of settlement_policy_id on settlement
    for each row
    when (new.settlement_policy_id is distinct from old.settlement_policy_id)
    execute function settlement_policy_stamp_is_frozen();


-- 환불은 취소 시점의 율·일수·금액을 박제한다(`D6`·`D7`). 구간표를 개정해도 지난 환불이 안 바뀌는 것이 그 박제의 목적이다.
create or replace function refund_stamps_are_frozen() returns trigger as $$
begin
    raise exception '환불의 율·일수·금액 박제는 고칠 수 없다: refund_id=%', old.refund_id;
end;
$$ language plpgsql;

create trigger refund_stamps_frozen
    before update on refund
    for each row
    when (new.tier_rate is distinct from old.tier_rate
       or new.days_before is distinct from old.days_before
       or new.fee_amount is distinct from old.fee_amount
       or new.refund_amount is distinct from old.refund_amount)
    execute function refund_stamps_are_frozen();


-- 회차 좌석의 가격은 오픈 때 등급에서 베낀다(`V6`). 공연이 값을 고쳐도 열린 회차는 안 바뀐다 — 예매 합계가 이 값을 믿는다.
create or replace function performance_seat_price_is_frozen() returns trigger as $$
begin
    raise exception '회차 좌석의 가격 박제는 고칠 수 없다: performance_seat_id=%', old.performance_seat_id;
end;
$$ language plpgsql;

create trigger performance_seat_price_frozen
    before update of price on performance_seat
    for each row
    when (new.price is distinct from old.price)
    execute function performance_seat_price_is_frozen();
