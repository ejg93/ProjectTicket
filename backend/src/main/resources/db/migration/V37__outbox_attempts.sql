-- 릴레이의 독 행이 발행을 막지 않게 시도를 센다(점검 17차 → `I17-4`).
--
-- 릴레이는 한 회에 100행을 잡고 하나라도 던지면 회 전체가 롤백돼 같은 배치를 매초 다시 집었다 — 보낼 수 없는 사건 하나
-- (`RecordTooLargeException`·구버전 파드가 모르는 `type`)가 배치 뒤의 사건을 영영 막았다. 이제 행마다 격리하고, 실패한 행은
-- 지수 대기(1초부터 두 배, 최대 5분)로 다시 시도하고 10회 뒤 주차한다. 주차는 `attempts >= 10` 그 자체라 칸을 따로 안 둔다.
-- 푸는 길은 사람의 `update outbox set attempts = 0, next_attempt_at = null where outbox_id = …` 다(`event-catalog.md` 「발행」).
--
-- 보존 트리거(`outbox_only_published_at_changes`, `V13`)는 사건 칸 일곱의 변경만 막아 새 칸은 트리거를 안 고쳐도 갱신된다.

alter table outbox
    add column attempts        int  not null default 0,
    add column last_error      text,
    add column next_attempt_at timestamptz,
    add constraint outbox_attempts_check check (attempts >= 0);

comment on column outbox.attempts is '발행 실패 수. 일시 오류(브로커 장애)는 안 센다. 10 이면 주차 — 사람이 0 으로 되돌린다';
comment on column outbox.last_error is '마지막 실패의 예외 클래스 이름. 메시지·페이로드는 안 남긴다(D10)';
comment on column outbox.next_attempt_at is '다음 시도 시각. null 이면 바로 — 실패마다 1초부터 두 배, 최대 5분';

-- 릴레이가 「같은 집합체의 앞선 미발행 행이 없다」를 본다. 집합체마다 맨 앞 행만 잡아 순서를 지킨다 —
-- 독 행 뒤의 같은 집합체 사건이 서고, 두 파드가 같은 집합체의 앞뒤 행을 나눠 잡지 않는다.
create index outbox_unpublished_aggregate_idx on outbox (aggregate_type, aggregate_id, outbox_id) where published_at is null;
