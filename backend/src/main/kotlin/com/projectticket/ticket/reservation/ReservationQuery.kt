package com.projectticket.ticket.reservation

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/** 예매 하나를 읽는다. **남의 것은 없는 것**이다 — 404 로 답하고 존재를 안 흘린다(`D5` 「403 이냐 404 냐」) */
@Component
class ReservationQuery(private val jdbc: JdbcClient) {

    fun get(reservationId: Long, accountId: Long): Reservation {
        val head = jdbc.sql(
            """
            select reservation_id, performance_id, status, total_amount, held_until, paying_until
              from reservation
             where reservation_id = :id and account_id = :account
            """,
        )
            .param("id", reservationId)
            .param("account", accountId)
            .query(Head::class.java)
            .optional()
            .orElseThrow { TicketException(ErrorCode.RESERVATION_NOT_FOUND) }

        val seats = jdbc.sql(
            """
            select performance_seat_id, price
              from reservation_seat
             where reservation_id = :id
             order by performance_seat_id
            """,
        ).param("id", reservationId).query(Seat::class.java).list().filterNotNull()

        return Reservation(
            reservationId = head.reservationId,
            performanceId = head.performanceId,
            status = ReservationStatus.of(head.status).code,
            totalAmount = head.totalAmount,
            heldUntil = head.heldUntil,
            payingUntil = head.payingUntil,
            seats = seats,
        )
    }

    /**
     * 선점·조회 응답. 멱등 재생이 이것을 JSON 으로 저장했다가 되살리므로 **필드를 빼면 옛 저장분이 못 읽힌다** —
     * 빼는 대신 `null` 로 남긴다(`D5` 「null 과 생략」).
     */
    data class Reservation(
        val reservationId: Long,
        val performanceId: Long,
        val status: String,
        val totalAmount: Int,
        val heldUntil: OffsetDateTime,
        val payingUntil: OffsetDateTime?,
        val seats: List<Seat>,
    )

    data class Seat(val performanceSeatId: Long, val price: Int)

    data class Head(
        val reservationId: Long,
        val performanceId: Long,
        val status: String,
        val totalAmount: Int,
        val heldUntil: OffsetDateTime,
        val payingUntil: OffsetDateTime?,
    )
}
