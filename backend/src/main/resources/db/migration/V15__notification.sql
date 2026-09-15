-- 알림(청크 26, D11 「알림 규약」). 첫 아웃박스 소비자다.
--
-- 채널은 이메일 하나고 발송은 모의다 — 보내는 대신 이 표에 본문을 남긴다. **본문은 이력이라 고치지 않는다.**

create table notification (
    notification_id bigint      generated always as identity primary key,

    -- 어느 사건이 만들었나. **멱등의 키**(D11 「소비」) — 같은 사건이 두 번 와도 행은 하나다.
    event_id        uuid        not null,
    -- 사건 이름 그대로. 코드의 `EventType` 과 같은 값이고 `outbox_type_check` 와 목록이 겹친다.
    event_type      text        not null,

    -- 받는 사람. 주소는 여기 없다 — **보낼 때 계정에서 읽는다**(D11). 탈퇴하면 그 자리에서 안 보낸다.
    account_id      bigint      not null references account (account_id) on delete restrict,

    subject         text        not null,
    body            text        not null,

    status          text        not null default 'pending',
    failure_reason  text,
    sent_at         timestamptz,

    created_at      timestamptz not null default now(),

    -- 회차 취소(17a)는 사건 하나에 수신자가 여럿이라 계정이 키에 든다(D11).
    constraint notification_event_account_key unique (event_id, account_id),

    constraint notification_event_type_check check (event_type in (
        'reservation.reserved', 'reservation.cancelled', 'performance.cancelled'
    )),
    constraint notification_status_check check (status in ('pending', 'sent', 'failed', 'skipped')),
    constraint notification_status_fields_check check (
        status = 'pending' and sent_at is null     and failure_reason is null
     or status = 'sent'    and sent_at is not null and failure_reason is null
     or status = 'failed'  and sent_at is null     and failure_reason is not null
     or status = 'skipped' and sent_at is null     and failure_reason is not null
    )
);

comment on table notification is '보낸 알림과 그 본문. 본문은 이력이라 안 고친다(D11)';
comment on column notification.event_id is '멱등의 키. 같은 사건이 두 번 와도 (event_id, account_id) 가 하나다';

-- 스윕이 안 보낸 것만 훑는다. 부분 인덱스라 보낸 것은 안 들어간다.
create index notification_pending_idx on notification (notification_id) where status = 'pending';
-- 내 알림을 보는 화면(44 뒤)이 계정별 최신순으로 읽는다.
create index notification_account_idx on notification (account_id, created_at desc);

-- 본문은 기록이다. 고칠 수 있는 것은 발송 결과(`status`·`sent_at`·`failure_reason`)뿐 —
-- 나중에 문구를 고치면 「그때 무엇을 보냈나」가 거짓이 된다.
create or replace function notification_body_is_frozen() returns trigger as $$
begin
    if new.event_id is distinct from old.event_id
        or new.event_type is distinct from old.event_type
        or new.account_id is distinct from old.account_id
        or new.subject is distinct from old.subject
        or new.body is distinct from old.body then
        raise exception '보낸 알림의 내용은 고칠 수 없다: notification_id=%', old.notification_id;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger notification_content_frozen
    before update on notification
    for each row execute function notification_body_is_frozen();
