-- 느린 쿼리를 미리 쌓아 둔다(청크 `42-0`).
--
-- **`42`(성능 측정)가 올 때 읽을 것이 이미 있게 하려는 것이다.** 그날부터 재기 시작하면
-- 하루치 표본으로 인덱스를 고르게 되고, 그 수치가 `D21`(성능 목표)의 입력이 된다.
--
-- 이 파일은 **볼륨이 비었을 때 한 번만** 돈다. 이미 `db-data` 가 있는 기계는
-- `docker compose down -v` 로 지워야 적용된다(`stack.md`).
create extension if not exists pg_stat_statements;
