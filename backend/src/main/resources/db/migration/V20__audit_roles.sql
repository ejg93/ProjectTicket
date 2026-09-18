-- 감사 로그의 권한을 가른다(4a, `D9`).
--
-- `V3` 의 보존 트리거가 막지 못하는 것이 있다고 그 자리에 적어 뒀다 — **테이블 주인은 트리거를 끌 수 있다.**
-- 그래서 앱이 도는 역할에서 `audit_log` 의 `update`·`delete`·`truncate` 를 아예 회수하고,
-- 파기(5a)만 다른 역할로 돌린다. 권한은 트리거보다 낮은 자리다: 문장이 **파싱되기 전에** 막힌다.
--
-- **로그인 없는 역할이다.** 접속 계정을 여기서 만들면 비밀번호가 저장소에 남는다 —
-- 앱은 지금 쓰는 계정으로 붙은 뒤 `SET ROLE` 로 이 역할을 입는다(`application.yml` 의 `connection-init-sql`).
-- 접속 계정 자체를 가르는 것은 배포가 정해진 뒤(36·38)에 할 일이고, 그때 이 역할에 로그인을 붙이면 된다.

create role ticket_app nologin;
create role ticket_purge nologin;

comment on role ticket_app is '앱이 입는 역할(4a). audit_log 는 insert·select 만 된다';
comment on role ticket_purge is '파기 배치가 입는 역할(4a). audit_log 를 지울 수 있는 유일한 역할이다';

-- 지금 접속 계정이 두 역할을 입을 수 있어야 한다. `current_user` 로 적어서 로컬·CI·운영의 계정 이름이 달라도 돈다.
do $$
begin
    execute format('grant ticket_app to %I', current_user);
    execute format('grant ticket_purge to %I', current_user);
end
$$;

-- 표를 읽고 쓰는 일반 권한. 새 표가 생기면 이 줄이 아니라 그 마이그레이션이 같이 준다.
grant usage on schema public to ticket_app, ticket_purge;
grant select, insert, update, delete on all tables in schema public to ticket_app;
grant usage, select on all sequences in schema public to ticket_app;
grant select on all tables in schema public to ticket_purge;

-- **여기가 이 마이그레이션의 전부다.** 감사는 쌓기만 한다.
revoke update, delete, truncate on audit_log from ticket_app;
grant delete on audit_log to ticket_purge;

-- 뒤에 생길 표에도 같은 규칙이 붙게 기본 권한을 정한다. 안 하면 `V21` 의 표만 조용히 권한이 없다.
alter default privileges in schema public
    grant select, insert, update, delete on tables to ticket_app;
alter default privileges in schema public
    grant usage, select on sequences to ticket_app;
alter default privileges in schema public
    grant select on tables to ticket_purge;
