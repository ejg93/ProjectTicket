package com.projectticket.ticket.reservation

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * 예매의 티켓. **번호로 조회하는 경로는 없다**(identifier-rules) — 예매 id 와 본인 확인을 거친다. 남의 예매는 404 다.
 * 아직 발권 전(`held`·`paying`)이면 빈 목록이다.
 */
@Component
class TicketQuery(private val jdbc: JdbcClient) {

    fun forReservation(reservationId: Long, accountId: Long): List<Ticket> {
        val owned = jdbc.sql("select count(*) from reservation where reservation_id = :id and account_id = :account")
            .param("id", reservationId).param("account", accountId).query(Long::class.java).single()
        if (owned == 0L) throw TicketException(ErrorCode.RESERVATION_NOT_FOUND)

        return jdbc.sql(
            """
            select t.ticket_number, rs.performance_seat_id, s.section, s.row_label, s.seat_number, rs.price, t.issued_at
              from ticket t
              join reservation_seat rs on rs.reservation_seat_id = t.reservation_seat_id
              join performance_seat ps on ps.performance_seat_id = rs.performance_seat_id
              join seat s on s.seat_id = ps.seat_id
             where rs.reservation_id = :id
             order by s.section, s.row_label, s.seat_number
            """,
        ).param("id", reservationId).query(Ticket::class.java).list().filterNotNull()
    }

    data class Ticket(
        val ticketNumber: String,
        val performanceSeatId: Long,
        val section: String,
        val rowLabel: String,
        val seatNumber: Int,
        val price: Int,
        val issuedAt: OffsetDateTime,
    )
}
