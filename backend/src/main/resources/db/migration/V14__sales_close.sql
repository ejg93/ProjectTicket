-- 판매 마감 시각과 자동 종료(청크 16a, ADR 0004·D7).
--
-- **달력일이 아니라 순간이다**(D7 「판매 마감은 절대 시각이다」). 「마감 지났나」는 `sales_close_at < now()` 하나고 날짜 경계가 없다 —
-- 취소 수수료(D6)가 KST 달력일 차인 것과 다른 종류의 시각이다.

alter table performance add column sales_close_at timestamptz;

-- 이미 있는 회차는 기본값으로 채운다. 이 마이그레이션 앞에 만들어진 것은 전부 기본 규칙 위에 있었다.
update performance set sales_close_at = starts_at - interval '1 hour';

alter table performance alter column sales_close_at set not null;

-- 셋의 순서가 어긋나면 살 수 없는 회차이거나 관람 뒤에도 파는 회차가 된다.
-- 기존 `performance_sales_before_start_check`(열림 < 시작)를 안 지운다 — 이 check 가 더 강하지만, 남겨 두면 어느 쪽이 걸렸는지가 문구로 갈린다.
alter table performance
    add constraint performance_sales_window_check check (sales_open_at < sales_close_at and sales_close_at < starts_at);

comment on column performance.sales_close_at is '판매 마감. 절대 시각이다(D7). 기본은 starts_at - 1시간이고 기획사가 바꾼다';

-- 기본값을 **트리거로 내린다**(사용자 선택). 회차를 만드는 입구가 곧 늘어난다(11 기획사 API·12 시드) —
-- 앱이 계산하면 입구마다 그 줄이 필요하고 하나가 빠뜨리면 not null 에서 죽는다. 여기 두면 빠뜨릴 자리가 없다.
--
-- `default` 로 못 한다 — 같은 행의 다른 컬럼(`starts_at`)을 봐야 한다.
create or replace function performance_sales_close_default() returns trigger as $$
begin
    if new.sales_close_at is null then
        new.sales_close_at := new.starts_at - interval '1 hour';
    end if;

    return new;
end;
$$ language plpgsql;

create trigger performance_default_sales_close
    before insert on performance
    for each row execute function performance_sales_close_default();

-- 자동 종료(16a)가 마감 지난 `open` 회차만 훑는다. 부분 인덱스라 닫힌 회차는 안 들어간다.
create index performance_sales_close_at_idx on performance (sales_close_at) where status = 'open';
