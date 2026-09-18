-- 「무엇이 물렀나」를 예매에 남긴다(26a).
--
-- 없을 때 나던 일: 회차 취소 알림이 **관객이 먼저 무른 예매까지 집었다**. 둘 다 `status = 'cancelled'` 라
-- 수신자 질의가 못 갈랐고, 그쪽은 수수료를 뗀 부분 환불인데 문구는 「전액·수수료 없음」으로 나갔다.
-- 알림만의 문제가 아니라 정산(27)·내 예매 조회(`D21`)가 같은 질문을 한다 — 그래서 열 하나로 내린다.

alter table reservation add column cancelled_by text;

comment on column reservation.cancelled_by is
    '무엇이 물렀나 — audience(관객이 취소), organizer(회차 취소), expired(시간이 지나 풀림). 살아 있는 예매는 null';

alter table reservation add constraint reservation_cancelled_by_check
    check (cancelled_by is null or cancelled_by in ('audience', 'organizer', 'expired'));

-- 기존 행. 회차 취소는 **환불 사유가 증언한다**(`performance_cancelled` 는 17a 가 그 경로에서만 쓴다).
update reservation r
   set cancelled_by = case
       when exists (
           select 1
             from payment p
             join refund rf on rf.payment_id = p.payment_id
            where p.reservation_id = r.reservation_id
              and rf.reason = 'performance_cancelled'
       ) then 'organizer'
       else 'audience'
   end
 where r.status = 'cancelled';

update reservation set cancelled_by = 'expired' where status = 'expired';

-- **전이 트리거가 이 열을 요구한다.** 앱 검증으로 두면 새 취소 경로가 생길 때 빠뜨리고,
-- 빠뜨린 것은 아무 오류도 안 내면서 알림 문구만 틀린다 — 그때는 이미 나간 뒤다(`D14` 「강제 지점」).
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

    return new;
end;
$$ language plpgsql;
