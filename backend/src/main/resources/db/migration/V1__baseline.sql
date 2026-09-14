-- 마이그레이션 도구가 실제로 붙었는지 확인하려고 두는 기준점이다.
-- 이 파일이 적용되면 flyway_schema_history 에 버전 1이 기록되고, 이후 스키마는 V2부터 쌓인다.
-- 테이블은 청크 3(계정)·7(공연장)부터 만든다. 여기서 미리 만들지 않는다.

-- 시각은 전부 timestamptz 로 저장하고 표시할 때만 지역 시각으로 바꾼다(D7).
-- 세션 기본값을 UTC 로 못박아 두면 접속 위치에 따라 값이 달라지지 않는다.
do $$
begin
    execute format('alter database %I set timezone to ''UTC''', current_database());
end
$$;
