-- 발권(청크 18). 확정된 예매의 좌석 하나에 티켓 하나. 번호가 외부에 노출되는 유일한 식별자다(D5, identifier-rules).
--
-- 번호는 `YYYYMMDD-XXXXXX` — KST 발권일 + 난수 6자. 난수는 `2-9` 와 `O`·`I` 를 뺀 알파벳(32자)이다.
-- 순번을 노출하면 총량과 증가 속도가 샌다(독일 전차 문제). 날짜는 고객도 아는 정보라 노출돼도 무해하고 CS 가 언제 표인지 바로 본다.

create table ticket (
    ticket_id           bigint      generated always as identity primary key,

    -- 좌석 하나에 티켓 하나. 기록(`reservation_seat`)을 가리킨다 — 포인터(`performance_seat.reservation_id`)는 풀리면 사라진다(D4 「두 표의 역할」).
    reservation_seat_id bigint      not null references reservation_seat (reservation_seat_id) on delete restrict,
    ticket_number       text        not null,

    issued_at           timestamptz not null default now(),

    constraint ticket_reservation_seat_id_key unique (reservation_seat_id),
    constraint ticket_ticket_number_key unique (ticket_number),
    -- 형식을 제약으로 내린다. 문서의 예시가 규칙을 어긴 것을 사람이 세 번 읽는 동안 못 본 적이 있다(identifier-rules).
    constraint ticket_ticket_number_format_check check (ticket_number ~ '^[0-9]{8}-[2-9A-HJ-NP-Z]{6}$')
);

comment on table ticket is '발권. 확정 예매의 좌석마다 하나. 번호는 KST 날짜 + 난수 6자';
comment on column ticket.ticket_number is '외부 노출 번호. 이 번호로 조회하는 경로를 만들지 않는다 — 번호가 곧 인증이 되면 안 된다';

-- 티켓은 기록이다. 예매가 취소돼도 행은 남는다 — 무엇이 발권됐었나는 정산·분쟁이 묻는다.
create or replace function ticket_append_only() returns trigger as $$
begin
    raise exception '티켓은 고칠 수 없다: ticket_id=%', old.ticket_id;
end;
$$ language plpgsql;

create trigger ticket_no_update
    before update on ticket
    for each row execute function ticket_append_only();
