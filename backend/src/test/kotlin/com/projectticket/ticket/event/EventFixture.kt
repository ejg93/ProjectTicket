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

    /** 판매 시작은 하루 전, 관람은 내일이 기본이다 */
    fun performance(eventId: Long, hallId: Long, startsInDays: Long = 1): Long =
        jdbc.sql(
            """
            insert into performance (event_id, hall_id, starts_at, sales_open_at)
            values (:event, :hall, now() + make_interval(days => :days), now() - interval '1 hour')
            returning performance_id
            """,
        ).param("event", eventId).param("hall", hallId).param("days", startsInDays.toInt())
            .query(Long::class.java).single()
}
