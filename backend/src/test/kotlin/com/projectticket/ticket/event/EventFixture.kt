package com.projectticket.ticket.event

import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 공연·회차 테스트가 쓰는 밑자리.
 *
 * **테스트마다 따로 만들지 않는다.** 같은 삽입을 여러 테스트가 베끼기 시작하면 컬럼이 늘 때 고칠 자리가 그만큼 는다.
 */
class EventFixture(private val jdbc: JdbcClient) {

    fun organizer(code: String = "test-org"): Long =
        jdbc.sql("insert into organizer (code, name) values (:code, '테스트기획') returning organizer_id")
            .param("code", code).query(Long::class.java).single()

    fun hall(name: String = "1관", venueName: String = "테스트홀"): Long {
        val venueId = jdbc.sql("insert into venue (name, address) values (:venue, '서울') returning venue_id")
            .param("venue", venueName).query(Long::class.java).single()
        return jdbc.sql("insert into hall (venue_id, name) values (:venue, :name) returning hall_id")
            .param("venue", venueId).param("name", name).query(Long::class.java).single()
    }

    /** 구역 하나에 좌석을 `count` 개. 열은 A 고 번호만 늘린다 */
    fun seats(hallId: Long, section: String, count: Int) {
        repeat(count) { index ->
            jdbc.sql(
                """
                insert into seat (hall_id, section, row_label, seat_number)
                values (:hall, :section, 'A', :number)
                """,
            ).param("hall", hallId).param("section", section).param("number", index + 1).update()
        }
    }

    fun event(organizerId: Long, title: String = "테스트 공연"): Long =
        jdbc.sql("insert into event (organizer_id, title) values (:org, :title) returning event_id")
            .param("org", organizerId).param("title", title).query(Long::class.java).single()

    fun grade(eventId: Long, code: String, price: Int): Long =
        jdbc.sql(
            "insert into seat_grade (event_id, code, name, price) values (:event, :code, :code, :price) returning seat_grade_id",
        ).param("event", eventId).param("code", code).param("price", price).query(Long::class.java).single()

    fun mapSection(eventId: Long, section: String, gradeId: Long) =
        jdbc.sql("insert into seat_grade_map (event_id, section, seat_grade_id) values (:event, :section, :grade)")
            .param("event", eventId).param("section", section).param("grade", gradeId).update()

    /**
     * 판매 시작은 하루 전, 관람은 내일이 기본이다. 마감(`sales_close_at`)은 트리거가 `starts_at - 1h` 로 채운다(`V14`).
     *
     * 판매 시작을 하루 전으로 둔 이유는 당일 회차(`startsInDays = 0`) 때문이다 — 한 시간 전이면 마감과 같아져 `performance_sales_window_check` 에 걸린다.
     */
    fun performance(eventId: Long, hallId: Long, startsInDays: Long = 1): Long =
        jdbc.sql(
            """
            insert into performance (event_id, hall_id, starts_at, sales_open_at)
            values (:event, :hall, now() + make_interval(days => :days), now() - interval '1 day')
            returning performance_id
            """,
        ).param("event", eventId).param("hall", hallId).param("days", startsInDays.toInt())
            .query(Long::class.java).single()

    fun account(email: String): Long =
        jdbc.sql("insert into account (email, password_hash, display_name, role) values (:email, 'x', '관객', 'audience') returning account_id")
            .param("email", email).query(Long::class.java).single()

    /** 열린 회차의 좌석 id 를 자리 순서대로 */
    fun performanceSeatIds(performanceId: Long): List<Long> =
        jdbc.sql(
            """
            select ps.performance_seat_id
              from performance_seat ps join seat s on s.seat_id = ps.seat_id
             where ps.performance_id = :id
             order by s.section, s.row_label, s.seat_number
            """,
        ).param("id", performanceId).query(Long::class.java).list().filterNotNull()

    /**
     * 좌석 하나를 `held` 로 잡은 예매. 선점 서비스를 안 거친다 — 서비스를 재는 테스트가 아닌 자리에서 「잡힌 좌석」이 필요할 때 쓴다.
     *
     * 예매·기록·좌석 셋을 **한 문장**으로 넣는다. 롤백 없는 바탕에서는 문장마다 커밋이라, 셋을 나누면 첫 커밋에서
     * 합계 트리거가 「좌석 없는 예매」로 터진다(`V7`).
     */
    fun hold(accountId: Long, performanceId: Long, section: String, seatNumber: Int): Long =
        jdbc.sql(
            """
            with target as (
                select ps.performance_seat_id, ps.price
                  from performance_seat ps join seat s on s.seat_id = ps.seat_id
                 where ps.performance_id = :performance and s.section = :section and s.seat_number = :number
            ), r as (
                insert into reservation (account_id, performance_id, total_amount, held_until)
                select :account, :performance, price, now() + interval '5 minutes' from target
                returning reservation_id, held_until
            ), rs as (
                insert into reservation_seat (reservation_id, performance_seat_id, price)
                select r.reservation_id, target.performance_seat_id, target.price from r, target
            )
            update performance_seat ps
               set status = 'held', held_until = r.held_until, reservation_id = r.reservation_id
              from r, target
             where ps.performance_seat_id = target.performance_seat_id
            returning r.reservation_id
            """,
        )
            .param("account", accountId).param("performance", performanceId)
            .param("section", section).param("number", seatNumber)
            .query(Long::class.java).single()

    /** `held` 예매를 전이표대로 `paying` 을 거쳐 `reserved` 로. 좌석도 `reserved` 로 */
    fun reserve(reservationId: Long) {
        jdbc.sql("update reservation set status = 'paying', paying_until = now() + interval '3 minutes' where reservation_id = :id")
            .param("id", reservationId).update()
        jdbc.sql("update reservation set status = 'reserved', reserved_at = now(), paying_until = null where reservation_id = :id")
            .param("id", reservationId).update()
        jdbc.sql("update performance_seat set status = 'reserved', held_until = null where reservation_id = :id")
            .param("id", reservationId).update()
    }
}
