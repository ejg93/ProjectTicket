-- 감사 로그와 동의 이력. 둘 다 「사건」을 적는 테이블이고 상태를 적지 않는다.

-- ─────────────────────────────────────────────────────────────
-- 감사 로그 — 누가 무엇을 했나
-- ─────────────────────────────────────────────────────────────
--
-- 관측 로그(`D10`)와 목적도 보관 위치도 다르다. 그쪽은 「이 요청이 어디서 느려졌나」를 파일에 남기고 지워도 되지만,
-- 이쪽은 DB 에 남기고 함부로 못 지운다. 무엇을 남기는지는 ADR 0003 이 정했다 —
-- 돈·권한·상태가 바뀌는 것만이다.
create table audit_log (
    audit_log_id     bigint generated always as identity primary key,

    -- 무슨 일이 있었나. `account.logged_in`·`reservation.confirmed` 같은 점 표기다.
    -- 종류별로 테이블을 안 나눈다 — 조회가 대부분 「이 사람이 무엇을 했나」라서 나누면 그때마다 union 을 한다.
    event_type       text        not null,

    -- 누가 했나. **외래키를 안 건다.** 계정이 파기돼도 이 기록은 남아야 감사가 성립하는데,
    -- 걸면 파기가 이 행까지 끌고 가거나 파기 자체가 막힌다.
    actor_account_id bigint,

    -- 무엇에 대해 했나. `reservation` + 예매 id 같은 것. 자원이 없는 사건이면 비어 있다.
    target_type      text,
    target_id        bigint,

    -- 사건마다 담을 것이 달라서 열로 못 뺀다. 조회 조건이 되는 값은 여기 두지 말고 위의 열로 뺀다 —
    -- jsonb 안을 조건으로 걸기 시작하면 무엇이 들어 있는지 아무도 모르는 채로 쿼리만 는다.
    detail           jsonb       not null default '{}'::jsonb,

    created_at       timestamptz not null default now()
);

comment on table audit_log is '누가 무엇을 했나. 보존 3년. 관측 로그(파일)와 다르다';
comment on column audit_log.actor_account_id is '외래키를 안 건다. 계정이 파기돼도 이 기록은 남아야 한다';

-- 「이 사람이 무엇을 했나」가 가장 흔한 조회다. 최근 것부터 본다.
create index audit_log_actor_idx on audit_log (actor_account_id, created_at desc);

-- 「이 예매에 무슨 일이 있었나」. 자원이 없는 사건은 인덱스에 안 들어간다.
create index audit_log_target_idx on audit_log (target_type, target_id, created_at desc)
    where target_type is not null;

-- 파기 배치가 오래된 것부터 훑는다.
create index audit_log_created_at_idx on audit_log (created_at);

-- 감사 로그는 고쳐 쓰지 않는다.
--
-- **강제 지점을 여기 둔 이유는 앱 밖으로도 들어올 수 있어서다.** 코드에 update 를 안 쓰는 것만으로는
-- `psql` 로 한 줄 고치는 것을 못 막고, 감사 로그는 바로 그것을 막으려고 있는 테이블이다.
--
-- **이 트리거가 막지 못하는 것을 밝혀 둔다.** 테이블 주인은 `alter table … disable trigger` 로 끌 수 있고,
-- 슈퍼유저는 `session_replication_role = replica` 로 건너뛴다. 그것까지 막으려면 앱이 쓰는 역할에서
-- `update`·`delete`·`truncate` 권한을 회수하고 파기 배치만 다른 역할로 돌려야 한다 — 역할을 가르는 것이라
-- 배포 형태가 정해진 뒤에 할 일이고, 청크 `4a` 가 든다.
--
-- **삭제는 보존 기간이 지난 행만 연다.** 전부 막으면 파기 배치가 돌 수 없고, 전부 열면 은폐가 된다.
-- 그 둘을 가르는 것이 나이다 — 3년은 `actor_account_id` 가 개인정보 파기의 예외로 남는 기간이라
-- 길수록 그 자체가 비용이다.
create or replace function audit_log_append_only() returns trigger as $$
begin
    if tg_op = 'UPDATE' then
        raise exception '감사 로그는 고칠 수 없다: audit_log_id=%', old.audit_log_id;
    end if;

    if old.created_at > now() - interval '3 years' then
        raise exception '보존 기간(3년) 안의 감사 로그는 지울 수 없다: audit_log_id=%', old.audit_log_id;
    end if;

    return old;
end;
$$ language plpgsql;

create trigger audit_log_no_update
    before update on audit_log
    for each row execute function audit_log_append_only();

create trigger audit_log_no_delete
    before delete on audit_log
    for each row execute function audit_log_append_only();

-- **행 트리거는 `truncate` 에 안 걸린다.** 한 줄씩 지우는 것은 막으면서 통째로 비우는 것을 여는 것은 앞뒤가 안 맞는다.
create or replace function audit_log_no_truncate() returns trigger as $$
begin
    raise exception '감사 로그는 truncate 할 수 없다';
end;
$$ language plpgsql;

create trigger audit_log_truncate_guard
    before truncate on audit_log
    for each statement execute function audit_log_no_truncate();

-- ─────────────────────────────────────────────────────────────
-- 동의 이력 — 무엇에 언제 동의했고 언제 철회했나
-- ─────────────────────────────────────────────────────────────
--
-- 계정 테이블의 불린 컬럼으로는 안 된다. 불린은 「지금 동의 상태」만 들고 있어서 동의 시점을 못 대고,
-- 철회하면 동의했던 사실 자체가 사라진다. 입증이 안 된다.
-- 그래서 상태가 아니라 사건을 적는다 — 동의도 한 행, 철회도 한 행이다.

-- 동의받을 항목. 코드가 아니라 데이터로 둔다. 항목이 늘 때 배포하지 않는다.
create table consent_item (
    consent_item_id      bigint generated always as identity primary key,

    -- 코드에서 참조하는 안정된 키. 개정돼도 안 바뀐다.
    code                 text        not null,

    -- 개정판. 개정하면 새 행이 생기고 옛 행은 남는다 — 남아야 「이 사람은 2판에 동의했다」가 성립한다.
    version              int         not null default 1,

    title                text        not null,

    -- 화면에 늘어놓는 순서. 기본키 순으로 두면 개정할 때 그 항목이 목록 끝으로 간다.
    sort_no              int         not null default 0,

    -- 개인정보 동의가 알려야 하는 넷(개인정보보호법 제15조제2항 — 목적·항목·기간·거부권과 불이익).
    -- 알리지 않고 받은 동의는 동의가 아니다.
    purpose              text,
    collected_items      text,
    retention_period     text,
    refusal_disadvantage text,

    -- 약관 본문(마크다운). 보여줄 원문이 시스템에 있어야 개정판을 남기는 설계가 쓰인다.
    body                 text,

    -- 필수 동의는 거부하면 가입이 안 된다. 선택은 거부해도 서비스를 준다.
    is_required          boolean     not null default false,

    effective_at         timestamptz not null default now(),
    created_at           timestamptz not null default now(),

    constraint consent_item_code_version_key unique (code, version),

    -- 정형 넷은 전부 있거나 전부 없다. 셋만 채우면 법이 요구한 하나가 빠진 채로 동의를 받는다.
    constraint consent_item_notice_check check (
        (purpose is null and collected_items is null
         and retention_period is null and refusal_disadvantage is null)
        or (purpose is not null and collected_items is not null
            and retention_period is not null and refusal_disadvantage is not null)
    ),

    -- 둘 다 비면 고지할 내용이 없는 항목이 된다. 거기 받은 동의는 무엇에 대한 것인지 모른다.
    constraint consent_item_content_check check (body is not null or purpose is not null)
);

comment on table consent_item is '동의받을 항목. (code, version) 이 한 행. 개정하면 새 행이 생기고 옛 행은 안 지운다';

create index consent_item_code_idx on consent_item (code, effective_at desc);

-- 동의·철회 사건. append-only 다.
--
-- update 로 갈면 이력이 그 자리에서 사라진다. 철회를 update 로 적으면 철회 시점은 남지만 동의 시점을 잃는다.
create table account_consent (
    account_consent_id bigint generated always as identity primary key,

    -- 계정을 물리 삭제하면 같이 지운다. `audit_log` 와 반대다 — 감사는 「누가 무엇을 했나」라 계정이 없어져도 남아야 하지만,
    -- 동의 이력은 그 사람의 개인정보 그 자체고 계약이 끝나면 입증할 상대가 없다.
    --
    -- 탈퇴로는 안 지워진다. 탈퇴는 `deleted_at` 을 채우는 update 고 cascade 는 delete 에만 걸린다.
    account_id         bigint      not null references account (account_id) on delete cascade,

    -- 어느 판에 동의했나. 판까지 가리켜야 개정 후 재동의가 필요한지 판단할 수 있다.
    consent_item_id    bigint      not null references consent_item (consent_item_id) on delete restrict,

    -- true=동의, false=철회·거부. **안 건드린 항목은 행이 아예 없다** — 거부와 무응답이 갈린다.
    granted            boolean     not null,

    -- 어디서 한 행동인가. 동의를 받은 화면이 무엇이었나가 분쟁에서 쟁점이 된다.
    source             text        not null,

    -- 동의 입증에 쓴다. 개인정보라 파기 대상이고 그래서 위 cascade 에 같이 걸린다.
    acted_ip           inet,

    acted_at           timestamptz not null default now(),

    constraint account_consent_source_check check (source in ('signup', 'mypage', 'withdraw'))
);

comment on table account_consent is '동의·철회 사건. append-only. 현재 상태는 current_consent 뷰가 만든다';

create index account_consent_current_idx on account_consent (account_id, consent_item_id, acted_at desc);

-- append-only 를 **글이 아니라 트리거로 든다.**
--
-- `audit_log` 와 같은 주장을 하면서 여기만 안 걸면 `update … set granted = false` 한 줄이
-- **이 설계가 지키려던 이력 그 자체**를 지운다. 철회는 행을 더하는 것이지 고치는 것이 아니다.
--
-- 삭제는 파기가 한다(계정 cascade). 그래서 `delete` 는 막지 않고 `update` 만 막는다.
create or replace function account_consent_append_only() returns trigger as $$
begin
    raise exception '동의 이력은 고칠 수 없다: account_consent_id=%. 철회는 행을 더한다', old.account_consent_id;
end;
$$ language plpgsql;

create trigger account_consent_no_update
    before update on account_consent
    for each row execute function account_consent_append_only();

-- 현재 동의 상태. 항목 코드별로 마지막 사건 하나를 고른다.
--
-- 뷰로 두는 이유는 이 `distinct on` 을 앱 여기저기가 베껴 쓰기 시작하면 정렬 키를 하나 빠뜨린 곳이
-- 조용히 옛 값을 읽기 때문이다. 정렬에 id 를 같이 넣는다 — 같은 트랜잭션에서 동의와 철회가 연달아 들어가면
-- `now()` 가 같은 값이라 `acted_at` 만으로는 순서가 안 정해진다.
create view current_consent as
select distinct on (ac.account_id, ci.code)
       ac.account_id,
       ci.code    as item_code,
       ci.version as item_version,
       ac.consent_item_id,
       ac.granted,
       ac.acted_at
  from account_consent ac
  join consent_item ci on ci.consent_item_id = ac.consent_item_id
 order by ac.account_id, ci.code, ac.acted_at desc, ac.account_consent_id desc;

comment on view current_consent is '항목 코드별 마지막 사건. 코드로 묶어서 개정 전 판에 한 동의도 여기 잡힌다';

-- 항목 시드. **셋이다**(ADR 0003) — 필수 둘과 선택 하나.
--
-- 마케팅을 채널별로 안 쪼갠다. 이 프로젝트의 발송 채널이 이메일 하나뿐이라(청크 26) 쪼갤 대상이 없고,
-- 야간 광고 동의도 안 만든다 — 물어볼 채널이 없는 동의를 받으면 그 동의가 무엇을 여는지 아무도 모른다.
--
-- 본문은 이 프로젝트가 실제로 하는 것만 적는다. 실제 예매처 문안을 베껴 오면
-- 스키마에 없는 것을 약속하게 되고, 그건 지킬 수 없는 약관이다.
insert into consent_item (code, title, is_required, sort_no, body) values
    ('terms_of_service', '이용약관', true, 10, $$
## 제1조 (목적)

이 약관은 회사가 운영하는 공연 예매 서비스의 이용 조건을 정합니다.

## 제2조 (예매의 성립)

**예매는 좌석을 선점한 뒤 결제가 승인된 시점에 성립합니다.**
선점만 한 상태에서 정해진 시간이 지나면 좌석은 자동으로 해제되며 예매는 성립하지 않습니다.

## 제3조 (취소와 환불)

**관람일을 기준으로 취소 수수료가 달라지며, 관람일 당일에는 취소할 수 없습니다.**
구간별 수수료율은 취소 화면에서 결제 전에 보여 줍니다.

## 제4조 (회원 탈퇴)

회원은 언제든지 탈퇴할 수 있습니다. 탈퇴 후 30일이 지나면 개인정보를 파기하며,
법령이 보존을 요구하는 거래기록은 해당 기간 동안 분리하여 보관합니다.
$$);

-- 개인정보 항목은 정형 넷을 채운다. 수집 항목에 접속 IP 가 들어 있다 —
-- `account_consent.acted_ip` 가 실제로 받는 값이고, 스키마에 있는데 고지에 없으면 근거 없이 받는 것이 된다.
insert into consent_item (code, title, is_required, sort_no,
                          purpose, collected_items, retention_period, refusal_disadvantage) values
    ('privacy_collect', '개인정보 수집·이용', true, 20,
     '회원 식별과 예매 처리, 공연 변경·취소 안내',
     '이메일, 이름, 비밀번호(암호화 저장), 동의 시점의 접속 IP',
     '탈퇴 후 30일까지. 법령이 보존을 요구하는 거래기록은 해당 기간 동안 분리 보관',
     '거부할 수 있으나 회원 가입이 되지 않습니다'),

    ('marketing_email', '광고성 정보 수신 (이메일)', false, 30,
     '신규 공연과 할인 안내',
     '이메일, 이름',
     '동의를 철회할 때까지',
     '거부해도 서비스 이용에는 제한이 없습니다');
