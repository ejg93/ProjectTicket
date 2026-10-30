-- 아웃박스의 사건·집합 짝과 멱등키 형식을 check 로 내린다(I13-8a).
--
-- `outbox_type_check`·`outbox_aggregate_type_check`(V13)는 따로라 `reservation.reserved` + `performance` 를 받았다.
-- 릴레이(`OutboxRelay`)는 행의 `aggregate_type` 을 버리고 `type` 에서 만들어서 그 행은 표만 보는 사람을 속인다.
-- 사건 이름의 앞 마디가 집합체다(`D11` 「봉투」 — `<집합체>.<과거형>`).
alter table outbox
    add constraint outbox_type_aggregate_check check (aggregate_type = split_part(type, '.', 1));

-- 앱은 UUIDv4 만 받는다(`IdempotencyKeys`, `D4` 「멱등키」). DB 는 길이 1..255 만 봐서 `psql`·새 입구가 아무 글자나 넣을 수 있었다.
-- 정규식은 `IdempotencyKeys.FORMAT_REGEX` 와 글자가 같다 — `AppDbConstraintTest.formats_match_the_database` 가 대조한다.
-- 기존 행은 앱이 늘 지켜 온 값이고 24시간 뒤 지워져서 옮길 것이 없다.
alter table idempotency_key
    add constraint idempotency_key_format_check check (key_value ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$');
