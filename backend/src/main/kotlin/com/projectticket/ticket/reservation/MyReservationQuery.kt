package com.projectticket.ticket.reservation

import com.projectticket.ticket.web.Paging
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * 내 예매 목록(`44a`, `D5` 「목록 조회」). **내 것만** — 조건이 SQL 에 있다(`account_id = :account`), 화면이 거르지 않는다.
 *
 * 정렬은 **만든 시각 내림차순 고정**이다(설계) — 방금 한 예매가 맨 위에 와야 하고, 고를 정렬이 없으니 `sort` 를 안 받는다.
 * 같은 시각이면 번호로 가른다 — 순서가 흔들리면 페이지 경계의 한 줄이 두 번 보인다.
 */
@Component
class MyReservationQuery(private val jdbc: JdbcClient) {

    fun list(accountId: Long, paging: Paging): MyReservationPage {
        val total = jdbc.sql("select count(*) from reservation where account_id = :account")
            .param("account", accountId).query(Int::class.java).single()

        val items = jdbc.sql(
            """
            select r.reservation_id, r.performance_id, e.title as event_title, p.starts_at, r.status, r.total_amount,
                   (select count(*) from reservation_seat s where s.reservation_id = r.reservation_id) as seat_count,
                   r.created_at
              from reservation r
              join performance p on p.performance_id = r.performance_id
              join event e on e.event_id = p.event_id
             where r.account_id = :account
             order by r.created_at desc, r.reservation_id desc
             limit :size offset :offset
            """,
        )
            .param("account", accountId)
            .param("size", paging.size)
            .param("offset", paging.offset)
            .query(MyReservation::class.java)
            .list()
            .filterNotNull()

        return MyReservationPage(items, paging.page, paging.size, total)
    }

    data class MyReservationPage(val items: List<MyReservation>, val page: Int, val size: Int, val total: Int)

    data class MyReservation(
        val reservationId: Long,
        val performanceId: Long,
        val eventTitle: String,
        val startsAt: OffsetDateTime,
        val status: String,
        val totalAmount: Int,
        val seatCount: Int,
        val createdAt: OffsetDateTime,
    )
}
