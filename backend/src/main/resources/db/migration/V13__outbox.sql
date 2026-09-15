-- 발행 대기 사건(청크 25, `D11`). **사실을 만든 트랜잭션이 이 행을 같이 커밋한다** —
-- 확정 없이 사건 없고, 사건 없이 확정 없다. 그것이 Transactional Outbox 가 사는 이유다.
--
-- 메시지를 트랜잭션 밖에서 보내면 「커밋은 됐는데 안 보냈다」와 「보냈는데 롤백됐다」가 둘 다 생긴다.
-- 여기서는 행이 곧 약속이고, 릴레이는 그 약속을 나중에 지킨다(at-least-once).

create table outbox (
    outbox_id      bigint      generated always as identity primary key,

    -- 소비자 멱등의 키(`D11`). 같은 사건이 두 번 나가도 소비자가 이 값으로 한 번만 처리한다.
    -- 릴레이가 발행 뒤 표시 전에 죽으면 실제로 두 번 나간다 — 그것을 막는 것이 아니라 받는 쪽에서 흡수한다.
    event_id       uuid        not null,

    type           text        not null,
    -- 페이로드 판. 호환되는 변경(필드 추가)은 안 올린다(`D11` 「버전」).
    version        int         not null default 1,

    -- **사건이 일어난 시각이다. 발행 시각이 아니다**(`D7` — 시계는 DB). 트랜잭션 시작 시각이라 한 트랜잭션의 사건들이 같은 값을 든다.
    occurred_at    timestamptz not null default now(),

    -- 파티션 키(28). 한 집합체의 사건이 순서를 지킨다.
    aggregate_type text        not null,
    aggregate_id   bigint      not null,

    payload        jsonb       not null,

    -- 발행된 시각. null 이면 아직 안 나갔다 — 릴레이가 이 열로 고른다.
    published_at   timestamptz,

    created_at     timestamptz not null default now(),

    constraint outbox_event_id_key unique (event_id),

    -- 값 목록은 코드의 `EventType` 과 여기 둘 다 있다(`D14` 「열거값을 어디에 두나」). 문서(`D11` 카탈로그)와의 대조는 `EventCatalogTest` 다.
    constraint outbox_type_check check (type in (
        'reservation.reserved', 'reservation.cancelled', 'performance.closed', 'performance.cancelled'
    )),
    constraint outbox_aggregate_type_check check (aggregate_type in ('reservation', 'performance')),
    constraint outbox_version_check check (version >= 1)
);

comment on table outbox is '발행 대기 사건(D11). 사실을 만든 트랜잭션이 같이 커밋한다. 발행 뒤 보존 기간이 지나면 지운다(25a)';
comment on column outbox.event_id is '소비자 멱등의 키. 릴레이가 두 번 보내도 소비자가 한 번만 처리한다';

-- 릴레이가 **안 나간 것만** 오래된 순으로 훑는다. 부분 인덱스라 발행된 행은 안 들어간다 —
-- 표가 커져도 훑는 비용이 대기 중인 수에 비례한다.
create index outbox_unpublished_idx on outbox (outbox_id) where published_at is null;

-- 보존 기간 청소(25a)가 훑는다.
create index outbox_published_at_idx on outbox (published_at) where published_at is not null;

-- 사건은 기록이다. **고칠 수 있는 것은 `published_at` 하나** — 그것이 릴레이가 하는 일의 전부다.
-- 페이로드나 이름을 나중에 고치면 「그때 무슨 일이 났나」가 거짓이 되고, 이미 나간 사건과 표가 어긋난다.
create or replace function outbox_only_published_at_changes() returns trigger as $$
begin
    if new.event_id is distinct from old.event_id
        or new.type is distinct from old.type
        or new.version is distinct from old.version
        or new.occurred_at is distinct from old.occurred_at
        or new.aggregate_type is distinct from old.aggregate_type
        or new.aggregate_id is distinct from old.aggregate_id
        or new.payload::text is distinct from old.payload::text then
        raise exception '사건은 고칠 수 없다: outbox_id=%. 고칠 수 있는 것은 published_at 뿐이다', old.outbox_id;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger outbox_append_only
    before update on outbox
    for each row execute function outbox_only_published_at_changes();
