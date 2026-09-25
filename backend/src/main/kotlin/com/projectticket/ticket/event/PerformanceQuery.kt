package com.projectticket.ticket.event

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * 회차 하나 읽기(`D5` 「공개 조회」, `40c`). 트랜잭션을 안 연다 — 읽기 쪽이다(`D14`).
 *
 * **상태를 안 가린다.** 회차 등록 201 의 `Location` 이 이 주소를 가리키는데 막 만든 회차는 `draft` 다 —
 * 판매 중만 보이면 방금 받은 `Location` 이 404 가 된다(RFC 9110). 살 수 있는지는 `status` 가 말한다.
 *
 * **공연 머리와 등급을 같이 싣는다** — 좌석 화면이 회차 번호 하나로 제목·공연장·가격표를 다 그리게.
 */
@Component
class PerformanceQuery(private val jdbc: JdbcClient) {

    fun detail(performanceId: Long): PerformanceDetail {
        val head = jdbc.sql(
            """
            select p.performance_id, p.event_id, e.title, o.name as organizer_name,
                   p.starts_at, p.sales_open_at, p.sales_close_at,
                   h.name as hall_name, v.name as venue_name, p.status
              from performance p
              join event e on e.event_id = p.event_id
              join organizer o on o.organizer_id = e.organizer_id
              join hall h on h.hall_id = p.hall_id
              join venue v on v.venue_id = h.venue_id
             where p.performance_id = :id
            """,
        ).param("id", performanceId).query(Head::class.java).optional().orElse(null)
            ?: throw TicketException(ErrorCode.PERFORMANCE_NOT_FOUND, "그런 회차가 없다: performance_id=$performanceId")

        val grades = jdbc.sql(
            """
            select code, name, price from seat_grade where event_id = :event order by sort_no, code
            """,
        ).param("event", head.eventId).query(EventQuery.Grade::class.java).list().filterNotNull()

        return PerformanceDetail(
            performanceId = head.performanceId,
            eventId = head.eventId,
            title = head.title,
            organizerName = head.organizerName,
            startsAt = head.startsAt,
            salesOpenAt = head.salesOpenAt,
            salesCloseAt = head.salesCloseAt,
            hallName = head.hallName,
            venueName = head.venueName,
            status = head.status,
            grades = grades,
        )
    }

    data class PerformanceDetail(
        val performanceId: Long,
        val eventId: Long,
        val title: String,
        val organizerName: String,
        val startsAt: OffsetDateTime,
        val salesOpenAt: OffsetDateTime,
        val salesCloseAt: OffsetDateTime,
        val hallName: String,
        val venueName: String,
        /** `draft`·`open`·`closed`(`V5`). 살 수 있는 것은 `open` 뿐이다 */
        val status: String,
        val grades: List<EventQuery.Grade>,
    )

    private data class Head(
        val performanceId: Long,
        val eventId: Long,
        val title: String,
        val organizerName: String,
        val startsAt: OffsetDateTime,
        val salesOpenAt: OffsetDateTime,
        val salesCloseAt: OffsetDateTime,
        val hallName: String,
        val venueName: String,
        val status: String,
    )
}
