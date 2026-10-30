-- 가격에 상한을 둔다(I13-3, ADR 0003 「가격 상한」). 앱은 `GradeRequest.price` 의 `@Max` 로 같은 값을 들고
-- `AppDbConstraintTest.the_price_range_is_in_both_places` 가 둘을 숫자로 대조한다.
--
-- 상한이 없으면 선점이 `sum(price)`(bigint)를 `reservation.total_amount int`(V7)에 넣다가 4석 합이 int 를 넘는 순간 500 이다.
-- 10,000,000 원이면 4석 합 40,000,000 이 int 안에 넉넉히 든다. 좌석 가격은 등급 가격을 오픈 때 복제한 것이라 같은 범위다.
alter table seat_grade drop constraint seat_grade_price_check;
alter table seat_grade add constraint seat_grade_price_check check (price between 0 and 10000000);

alter table performance_seat drop constraint performance_seat_price_check;
alter table performance_seat add constraint performance_seat_price_check check (price between 0 and 10000000);
