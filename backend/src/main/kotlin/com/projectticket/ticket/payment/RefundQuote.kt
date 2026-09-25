package com.projectticket.ticket.payment

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.reservation.ReservationStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 관객 취소의 환불 계산 **한 곳**(`44a`, `D6`). 취소([RefundTransitionService.request])와 미리보기([preview])가 같은 [amounts] 를 부른다 —
 * 두 벌이면 화면이 보여 준 액수와 실제 환불액이 갈린다.
 *
 * 구간은 행에서 고르고(`refund_fee_tier`) 산수는 [RefundPolicy] 가 한다. 시각은 부르는 쪽이 DB 의 `now()` 로 준다(`D7` 「시계는 DB 다」).
 */
@Component
class RefundQuote(private val jdbc: JdbcClient) {

    /** 취소 한 번의 액수. 당일이면 [amounts] 가 `null` 이다 — 구간이 없다 */
    data class Amounts(val daysBefore: Int, val tierRate: BigDecimal, val feeAmount: Int, val refundAmount: Int)

    fun amounts(startsAt: OffsetDateTime, at: OffsetDateTime, paymentAmount: Int): Amounts? {
        val daysBefore = RefundPolicy.daysBefore(startsAt, at)
        val rate = tierRateFor(daysBefore) ?: return null
        val fee = RefundPolicy.fee(paymentAmount, rate)
        return Amounts(daysBefore, rate, fee, paymentAmount - fee)
    }

    /**
     * 지금 취소하면 얼마인가(`GET /api/reservations/{id}/refund-preview`). **남의 것은 없는 것**이다 — 404(`D5`).
     *
     * 취소할 수 없으면 `cancellable = false` 와 **취소가 받을 `type` 슬러그**를 `reason` 에 싣는다 — 화면이 같은 문구 표로 가른다.
     */
    fun preview(reservationId: Long, accountId: Long): Preview {
        val row = jdbc.sql(
            """
            select r.reservation_id, r.status, p.starts_at, now() as at,
                   (select pay.amount from payment pay where pay.reservation_id = r.reservation_id and pay.status = :approved) as payment_amount
              from reservation r
              join performance p on p.performance_id = r.performance_id
             where r.reservation_id = :id and r.account_id = :account
            """,
        )
            .param("approved", PaymentStatus.APPROVED.code)
            .param("id", reservationId)
            .param("account", accountId)
            .query(Row::class.java)
            .optional()
            .orElseThrow { TicketException(ErrorCode.RESERVATION_NOT_FOUND) }

        val status = ReservationStatus.of(row.status)
        val daysBefore = RefundPolicy.daysBefore(row.startsAt, row.at)
        if (status != ReservationStatus.RESERVED || row.paymentAmount == null) {
            return Preview(reservationId, status.code, row.paymentAmount, daysBefore, cancellable = false, reason = ErrorCode.INVALID_TRANSITION.slug)
        }
        val amounts = amounts(row.startsAt, row.at, row.paymentAmount)
            ?: return Preview(reservationId, status.code, row.paymentAmount, daysBefore, cancellable = false, reason = ErrorCode.CANCEL_WINDOW_CLOSED.slug)
        return Preview(
            reservationId = reservationId,
            status = status.code,
            paymentAmount = row.paymentAmount,
            daysBefore = amounts.daysBefore,
            tierRate = amounts.tierRate,
            feeAmount = amounts.feeAmount,
            refundAmount = amounts.refundAmount,
            cancellable = true,
            reason = null,
        )
    }

    /**
     * 지금 효력 있는 판에서 `days_before >= days_before_min` 인 가장 큰 구간(`D6` 「고르는 규칙」). 없으면 당일이다.
     * 판은 `effective_at <= now()` 인 최신 — 개정하면 새 판을 넣고 옛 판은 남긴다.
     */
    private fun tierRateFor(daysBefore: Int): BigDecimal? =
        jdbc.sql(
            """
            select rate
              from refund_fee_tier
             where effective_at = (select max(effective_at) from refund_fee_tier where effective_at <= now())
               and days_before_min <= :days
             order by days_before_min desc
             limit 1
            """,
        )
            .param("days", daysBefore)
            .query(BigDecimal::class.java)
            .optional()
            .orElse(null)

    /** 액수 칸은 취소할 수 있을 때만 찬다. 없는 것은 `null` 이다 — 0 으로 내리면 「환불 0원」과 안 갈린다(`D5`) */
    data class Preview(
        val reservationId: Long,
        val status: String,
        val paymentAmount: Int?,
        val daysBefore: Int,
        val tierRate: BigDecimal? = null,
        val feeAmount: Int? = null,
        val refundAmount: Int? = null,
        val cancellable: Boolean,
        /** 취소가 받을 `type` 슬러그(`invalid-transition`·`cancel-window-closed`). 취소할 수 있으면 `null` */
        val reason: String?,
    )

    private data class Row(
        val reservationId: Long,
        val status: String,
        val startsAt: OffsetDateTime,
        val at: OffsetDateTime,
        val paymentAmount: Int?,
    )
}
