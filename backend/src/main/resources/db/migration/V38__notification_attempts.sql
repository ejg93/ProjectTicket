-- 알림 발송의 일시 실패를 다시 시도한다(점검 17차 → `I17-9`).
--
-- 스윕은 발송이 실패하면 곧 `failed` 로 찍었고 `V29` 트리거가 `failed` 를 끝 상태로 막는다 — SMTP 가 잠깐 안 받은 메일도 영영 안 나갔다.
-- 일시 실패는 `pending` 에 둔 채 시도 수를 세고 다음 시도 시각을 미룬다(30초부터 두 배). 5회째 실패가 `failed` 다.
-- 반송(받는 곳 없음)은 다시 해도 같아서 지금처럼 바로 `failed` 다. `V29` 는 안 고친다 — `pending → failed` 만 쓴다.
-- `pending` 은 `failure_reason` 이 비어야 한다(`notification_status_fields_check`, `V15`) — 마지막 일시 실패 사유는 안 남긴다.

alter table notification
    add column attempts        int not null default 0,
    add column next_attempt_at timestamptz,
    add constraint notification_attempts_check check (attempts >= 0);

comment on column notification.attempts is '일시 실패 수. 5 회째 실패면 failed — 반송은 세지 않고 바로 failed';
comment on column notification.next_attempt_at is '다음 발송 시도 시각. null 이면 바로 — 일시 실패마다 30초부터 두 배';
