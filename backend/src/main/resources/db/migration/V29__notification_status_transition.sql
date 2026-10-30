-- 알림 상태 전이를 DB 가 막는다(I13-7b). 전이표는 `state-machines.md` 「알림」이고 `StateMachineDocTest` 가 이 함수와 대조한다.
--
-- 지금까지 DB 는 칸 짝(`notification_status_fields_check`, V15)만 봤다. 그래서 `sent→pending`(보낸 것을 다시 보냄)·
-- `failed→sent` 를 받았다. 막는 것은 `NotificationStore` 의 `status = pending` 조건부 UPDATE 셋뿐이었다(`D14` 축 2).
--
-- 정상 경로는 셋이다(2026-10-31): 발송 pending→sent · 실패 pending→failed · 탈퇴 등으로 건너뜀 pending→skipped.
-- 끝 상태에서 나가는 경로는 없다. 실패를 다시 보내는 경로가 생기면 그때 `failed→pending` 을 표와 여기에 같이 더한다.
-- 같은 상태로의 갱신은 통과한다(V26 꼴).
create or replace function notification_status_transition() returns trigger as $$
begin
    if old.status = new.status then
        return new;
    end if;

    if not (old.status = 'pending' and new.status in ('sent', 'failed', 'skipped')) then
        raise exception '할 수 없는 알림 상태 전이다: % -> % (notification_id=%)', old.status, new.status, old.notification_id;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger notification_status_transition
    before update on notification
    for each row
    execute function notification_status_transition();
