package com.projectticket.ticket.event

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.web.OrderBy
import com.projectticket.ticket.web.Paging
import java.time.OffsetDateTime
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * 공연 목록·상세 읽기(`D5` 「공개 조회」). 트랜잭션을 안 연다 — 읽기 쪽이다(`D14`).
 *
 * **판매 중인 회차가 있는 공연만 보인다.** `draft` 는 기획사가 아직 안 연 것이고, `closed`·`cancelled` 는
 * 살 수 없다 — 목록에 두면 화면이 들어가서야 살 것이 없다는 것을 안다.
 *
 * **가격은 `seat_grade` 를 읽는다.** 열린 회차의 박제값([SeatQuery] 가 읽는 `performance_seat.price`)이 아니라
 * 공연이 지금 내건 값이다. 목록은 「얼마부터」를 보여주는 자리라 회차마다 다른 값을 하나로 못 줄인다 —
 * 실제로 낼 값은 좌석을 고른 뒤 회차의 박제값이 정한다.
 */
@Component
class EventQuery(private val jdbc: JdbcClient) {

    fun list(paging: Paging): EventPage {
        val order = OrderBy.of(paging.sort, SORTABLE, "starts_at")

        val total = jdbc.sql(
            """
            select count(*) from event e
             where exists (select 1 from performance p where p.event_id = e.event_id and p.status = 'open')
            """,
        ).query(Int::class.java).single()

        // `order by` 에 박히는 것은 우리가 쓴 조각이다 — 요청의 글자는 [OrderBy] 의 허용 목록을 지나며 버려진다.
        val items = jdbc.sql(
            """
            select e.event_id,
                   e.title,
                   o.name as organizer_name,
                   min(p.starts_at) as starts_at,
                   count(p.performance_id) as performance_count,
                   (select min(g.price) from seat_grade g where g.event_id = e.event_id) as min_price
              from event e
              join organizer o on o.organizer_id = e.organizer_id
              join performance p on p.event_id = e.event_id and p.status = 'open'
             group by e.event_id, e.title, o.name, e.created_at
             order by ${order.sql}, e.event_id
             limit :size offset :offset
            """,
        ).param("size", paging.size).param("offset", paging.offset).query(EventSummary::class.java).list().filterNotNull()

        return EventPage(items, paging.page, paging.size, total)
    }

    /**
     * 공연 하나와 **판매 중인 회차**.
     *
     * 회차가 하나도 안 열린 공연은 404 가 아니라 빈 목록이다 — 공연은 있는데 지금 살 것이 없는 것이라
     * 「없는 주소」와 뜻이 다르다(`D5` 「null 과 생략」).
     */
    fun detail(eventId: Long): EventDetail {
        val event = jdbc.sql(
            """
            select e.event_id, e.title, o.name as organizer_name
              from event e
              join organizer o on o.organizer_id = e.organizer_id
             where e.event_id = :id
            """,
        ).param("id", eventId).query(EventHead::class.java).optional().orElse(null)
            ?: throw TicketException(ErrorCode.EVENT_NOT_FOUND, "그런 공연이 없다: event_id=$eventId")

        val grades = jdbc.sql(
            """
            select code, name, price from seat_grade where event_id = :id order by sort_no, code
            """,
        ).param("id", eventId).query(Grade::class.java).list().filterNotNull()

        val performances = jdbc.sql(
            """
            select p.performance_id, p.starts_at, p.sales_open_at, p.sales_close_at, h.name as hall_name, v.name as venue_name
              from performance p
              join hall h on h.hall_id = p.hall_id
              join venue v on v.venue_id = h.venue_id
             where p.event_id = :id and p.status = 'open'
             order by p.starts_at
            """,
        ).param("id", eventId).query(PerformanceLine::class.java).list().filterNotNull()

        return EventDetail(event.eventId, event.title, event.organizerName, grades, performances)
    }

    data class EventPage(val items: List<EventSummary>, val page: Int, val size: Int, val total: Int)

    data class EventSummary(
        val eventId: Long,
        val title: String,
        val organizerName: String,
        val startsAt: OffsetDateTime,
        val performanceCount: Int,
        /** 등급을 아직 안 만든 공연은 `null` 이다. 0 으로 내리면 **공짜 공연과 안 갈린다**(`D5`) */
        val minPrice: Int?,
    )

    data class EventDetail(
        val eventId: Long,
        val title: String,
        val organizerName: String,
        val grades: List<Grade>,
        val performances: List<PerformanceLine>,
    )

    data class Grade(val code: String, val name: String, val price: Int)

    data class PerformanceLine(
        val performanceId: Long,
        val startsAt: OffsetDateTime,
        val salesOpenAt: OffsetDateTime,
        val salesCloseAt: OffsetDateTime,
        val hallName: String,
        val venueName: String,
    )

    private data class EventHead(val eventId: Long, val title: String, val organizerName: String)

    private companion object {
        /**
         * 요청에 쓰는 이름 → `order by` 조각(`D5` 「목록 조회」의 표).
         *
         * `starts_at` 은 **첫 회차**다 — 묶은 줄이라 `min()` 이고, 같은 날이 여럿이면 공연 번호로 가른다.
         * 순서가 매 요청 흔들리면 페이지 경계의 한 줄이 두 번 보이거나 아예 안 보인다.
         */
        val SORTABLE = mapOf(
            "starts_at" to "min(p.starts_at)",
            "created_at" to "e.created_at",
        )
    }
}
