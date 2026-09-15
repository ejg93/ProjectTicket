-- 표 둘을 걸치는 등식 둘(청크 13a, 점검 I1 이 찾았다). 앱만 지키고 있던 자리다.
--
-- **check 로 못 건다.** 둘 다 다른 표의 행을 봐야 한다(D14 「불변식 — 어디에 거나」). 지연도 아니다 —
-- 삽입하는 순간 양쪽 행이 이미 있어서 그 자리에서 판정할 수 있다. 합계 트리거(V7)가 지연인 것과 다른 이유가 이것이다.

-- 결제 금액은 예매 합계와 같다.
--
-- 지금은 `PaymentTransitionService` 가 `startPaying` 이 돌려준 값을 그대로 넣어서 어긋날 일이 없는데,
-- **금액을 요청에서 받는 입구가 생기는 날** 그 검사를 빠뜨리면 원하는 금액으로 결제된다(D9 「입력과 출력」).
-- 그 입구를 안 만드는 것이 지금 결정이고, 이 트리거는 그 결정이 코드에서 새는 것을 막는다.
create or replace function payment_amount_matches_reservation() returns trigger as $$
declare
    v_total int;
begin
    select total_amount into v_total from reservation where reservation_id = new.reservation_id;

    if v_total <> new.amount then
        raise exception '결제 금액이 예매 합계와 다르다: reservation_id=%, amount=%, total_amount=%',
            new.reservation_id, new.amount, v_total;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger payment_amount_matches_reservation_check
    before insert on payment
    for each row execute function payment_amount_matches_reservation();


-- 예매 좌석 기록의 좌석은 그 예매의 회차 것이다.
--
-- 선점(13)은 `lockInIdOrder` 가 `performance_id` 로 걸러서 다른 회차 좌석이 못 들어오지만, 그것은 앱 검증(3위)이다.
-- 어긋나면 **정산(27)이 남의 회차 매출을 센다** — `reservation_seat` 가 회차를 직접 안 들고 예매를 거쳐 가리키기 때문에
-- 조인 하나만 잘못 써도 드러나지 않는다.
create or replace function reservation_seat_same_performance() returns trigger as $$
declare
    v_reservation_performance bigint;
    v_seat_performance        bigint;
begin
    select performance_id into v_reservation_performance from reservation where reservation_id = new.reservation_id;
    select performance_id into v_seat_performance from performance_seat where performance_seat_id = new.performance_seat_id;

    if v_reservation_performance <> v_seat_performance then
        raise exception '다른 회차의 좌석이다: reservation_id=% 는 performance_id=%, 좌석은 %',
            new.reservation_id, v_reservation_performance, v_seat_performance;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger reservation_seat_performance_check
    before insert on reservation_seat
    for each row execute function reservation_seat_same_performance();
