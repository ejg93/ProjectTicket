-- 계정. 관객·기획사 구성원·관리자가 전부 여기 있고 역할 하나로 갈린다.
--
-- ProjectShop 의 user/role/permission 다섯 테이블을 안 가져왔다(ADR 0002). 여기 권한은 역할 셋뿐이라
-- 행 단위 스코프 판정이 없고, 역할이 계정에 컬럼 하나로 붙는 것이 가장 낮은 강제 지점이다.
-- 기획사 소속(어느 기획사인가)은 organizer_member(청크 7)가 따로 든다 — 역할이 '기획사'라는 것과
-- '어느 기획사'인가는 축이 다르다.

-- updated_at 을 손으로 넣는 걸 잊어도 값이 어긋나지 않게 트리거로 박는다.
create or replace function set_updated_at() returns trigger as $$
begin
    new.updated_at := now();
    return new;
end;
$$ language plpgsql;

-- 이메일·이름·비밀번호 해시가 not null 이 아닌 이유는 파기 때문이다.
-- 탈퇴 유예가 지나면 이 셋을 null 로 비운다. 행 자체는 안 지운다 — 예매가 account_id 로 계정을 가리키고
-- 거래 기록은 5년 남는다. 지키려는 것은 "모든 계정에 이메일이 있다" 가 아니라
-- "살아 있는 계정에 이메일이 있다" 이고, 그 행 조건은 아래 check 가 정확히 적는다.
--
-- 수명(deleted_at)과 업무 상태(status)를 한 컬럼에 안 섞는다. 섞으면 복구할 때 이전 상태를 잃고,
-- "살아 있는 것" 조건이 상태가 늘 때마다 흔들린다.
create table account (
    account_id    bigint generated always as identity primary key,
    email         text,
    password_hash text,
    display_name  text,

    -- 역할 하나. 관리자는 시드로만 만든다(가입 경로로 못 얻는다).
    role          text        not null default 'audience',

    -- 업무 상태만 담는다. 탈퇴는 여기가 아니라 deleted_at 이다.
    status        text        not null default 'active',

    -- 수명. null 이면 존재한다.
    deleted_at    timestamptz,

    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),

    constraint account_role_check   check (role in ('audience', 'organizer', 'admin')),
    constraint account_status_check check (status in ('active', 'suspended')),

    -- 살아 있는 계정에는 이 셋이 있다. 가입 코드의 버그로 이메일 없는 계정이 생겨도 DB 가 막는다.
    constraint account_alive_fields_check check (
        deleted_at is not null
        or (email is not null and password_hash is not null and display_name is not null)
    ),

    -- RFC 5321 이 주소를 254 옥텟으로 제한한다. 앱 검증(EmailAddress)과 같은 값이다 —
    -- 앱 검증은 배치·시드·psql 로 들어오는 것을 못 막아서 여기가 더 낮은 강제 지점이다.
    constraint account_email_length_check check (email is null or length(email) <= 254),
    constraint account_display_name_length_check check (display_name is null or length(display_name) <= 50)
);

comment on column account.deleted_at is '수명. null 이면 존재한다. 업무 상태(status)와 축이 다르다';
comment on column account.role is '역할 하나. 기획사 소속은 organizer_member 가 든다';

-- 대소문자만 다른 이메일로 두 번 가입하는 걸 막는다. null 을 빼는 부분 인덱스다 —
-- 안 그러면 파기된 계정끼리 null 로 충돌한다.
create unique index account_email_key on account (lower(email)) where email is not null;

-- 살아 있는 행만 고르는 조회가 대부분이라 부분 인덱스를 깐다.
create index account_alive_idx on account (account_id) where deleted_at is null;

create trigger account_set_updated_at
    before update on account
    for each row execute function set_updated_at();
