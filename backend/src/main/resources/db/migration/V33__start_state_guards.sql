-- 환불·정산서·알림은 전이표의 생성 상태로만 태어난다(`D3` 생성 행, 점검 15차 `I15` → `I15-2`).
--
-- 전이 트리거(`V25`·`V29`)는 update 만 본다. 생성 행 다섯 중 DB 가 막던 것은 예매(`V7`)·회차(`V5`) 둘뿐이라
-- 환불 `done`·정산서 `paid`·알림 `sent` 를 바로 넣으면 전이를 통째로 건너뛴다 — 칸 짝 check 는 열 조합만 본다.
-- 앱 insert 는 셋 다 기본값에 맡겨서 지금은 안 갈린다. 넣는 입구가 하나 더 생기는 날(수기 환불·재정산) 여기서 막힌다.

create or replace function refund_starts_as_requested() returns trigger as $$
begin
    if new.status <> 'requested' then
        raise exception '환불은 requested 로만 만들 수 있다: status=%', new.status;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger refund_starts_requested
    before insert on refund
    for each row execute function refund_starts_as_requested();


create or replace function settlement_starts_as_scheduled() returns trigger as $$
begin
    if new.status <> 'scheduled' then
        raise exception '정산서는 scheduled 로만 만들 수 있다: status=%', new.status;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger settlement_starts_scheduled
    before insert on settlement
    for each row execute function settlement_starts_as_scheduled();


create or replace function notification_starts_as_pending() returns trigger as $$
begin
    if new.status <> 'pending' then
        raise exception '알림은 pending 으로만 만들 수 있다: status=%', new.status;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger notification_starts_pending
    before insert on notification
    for each row execute function notification_starts_as_pending();
