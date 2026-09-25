package com.projectticket.ticket.event

import com.projectticket.ticket.web.Paging
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * 기획사가 **자기 공연**을 읽는다(`45a`, `D5`). 공개 조회([EventQuery])와 가른 이유는 둘이다 —
 * 공개는 판매 중 회차가 있는 공연만 내지만 기획사는 **오픈 전 것도** 봐야 하고, 공개에는 팔린 수가 없다.
 *
 * **내 것만**이 SQL 조건이다(`organizer_member` 조인). 상세는 [OrganizerMembership.requireEvent] 가 남의 것을 404 로 막는다.
 */
@Component
class OrganizerEventQuery(private val jdbc: JdbcClient, private val membership: OrganizerMembership) {

    /** 내가 속한 기획사들의 공연. 만든 시각 내림차순 고정 — 방금 등록한 것이 맨 위다 */
    fun list(accountId: Long, paging: Paging): OrganizerEventPage {
        val total = jdbc.sql(
            """
            select count(*) from event e
              join organizer_member m on m.organizer_id = e.organizer_id and m.account_id = :account
            """,
        ).param("account", accountId).query(Int::class.java).single()

        val items = jdbc.sql(
            """
            select e.event_id, e.title, o.organizer_id, o.name as organizer_name, e.created_at,
                   (select count(*) from performance p where p.event_id = e.event_id) as performance_count
              from event e
              join organizer o on o.organizer_id = e.organizer_id
              join organizer_member m on m.organizer_id = e.organizer_id and m.account_id = :account
             order by e.created_at desc, e.event_id desc
             limit :size offset :offset
            """,
        )
            .param("account", accountId)
            .param("size", paging.size)
            .param("offset", paging.offset)
            .query(OrganizerEventSummary::class.java)
            .list()
            .filterNotNull()

        return OrganizerEventPage(items, paging.page, paging.size, total)
    }

    /**
     * 공연 하나와 **모든 상태의** 회차. 회차마다 판매 기간·홀 좌석 수·팔린 수.
     * 팔린 수는 `reserved` 좌석만 센다 — `held` 는 선점일 뿐 아직 돈이 안 들어왔다(`D3`).
     */
    fun detail(accountId: Long, eventId: Long): OrganizerEventDetail {
        membership.requireEvent(accountId, eventId)

        val head = jdbc.sql(
            """
            select e.event_id, e.title, o.organizer_id, o.name as organizer_name
              from event e join organizer o on o.organizer_id = e.organizer_id
             where e.event_id = :id
            """,
        ).param("id", eventId).query(Head::class.java).single()

        val grades = jdbc.sql("select code, name, price from seat_grade where event_id = :id order by sort_no, code")
            .param("id", eventId).query(EventQuery.Grade::class.java).list().filterNotNull()

        val performances = jdbc.sql(
            """
            select p.performance_id, p.status, p.starts_at, p.sales_open_at, p.sales_close_at,
                   h.name as hall_name, v.name as venue_name,
                   (select count(*) from seat s where s.hall_id = p.hall_id) as seat_count,
                   (select count(*) from performance_seat ps where ps.performance_id = p.performance_id and ps.status = :reserved) as sold_count
              from performance p
              join hall h on h.hall_id = p.hall_id
              join venue v on v.venue_id = h.venue_id
             where p.event_id = :id
             order by p.starts_at
            """,
        )
            .param("id", eventId)
            .param("reserved", PerformanceSeatStatus.RESERVED.code)
            .query(OrganizerPerformance::class.java)
            .list()
            .filterNotNull()

        return OrganizerEventDetail(head.eventId, head.title, head.organizerId, head.organizerName, grades, performances)
    }

    data class OrganizerEventPage(val items: List<OrganizerEventSummary>, val page: Int, val size: Int, val total: Int)

    data class OrganizerEventSummary(
        val eventId: Long,
        val title: String,
        val organizerId: Long,
        val organizerName: String,
        val createdAt: OffsetDateTime,
        val performanceCount: Int,
    )

    data class OrganizerEventDetail(
        val eventId: Long,
        val title: String,
        val organizerId: Long,
        val organizerName: String,
        val grades: List<EventQuery.Grade>,
        val performances: List<OrganizerPerformance>,
    )

    data class OrganizerPerformance(
        val performanceId: Long,
        /** `draft`·`open`·`closed`·`cancelled` */
        val status: String,
        val startsAt: OffsetDateTime,
        val salesOpenAt: OffsetDateTime,
        val salesCloseAt: OffsetDateTime,
        val hallName: String,
        val venueName: String,
        /** 홀의 좌석 수 — 오픈 전에는 회차 좌석이 아직 복제되지 않아서 홀에서 센다 */
        val seatCount: Int,
        val soldCount: Int,
    )

    private data class Head(val eventId: Long, val title: String, val organizerId: Long, val organizerName: String)
}
