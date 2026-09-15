package com.projectticket.ticket.payment

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.event.PerformanceSeatStatus
import com.projectticket.ticket.reservation.ReservationStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 관객 취소의 트랜잭션 둘 — ① `reserved → cancelled` + 환불 행(`requested`) + 좌석 해제, ③ 환불 `done`.
 * ②(PG 환불)는 [RefundService] 가 트랜잭션 밖에서 한다(`D4` 「트랜잭션 경계」).
 *
 * 수수료 계산은 **① 안에서, DB 의 `now()` 로** 한다(`D7` 「시계는 DB 다」). 구간은 행에서 고르고(`refund_fee_tier`) 산수는 [RefundPolicy] 가 한다.
 */
@Service
class RefundTransitionService(private val jdbc: JdbcClient, private val auditLog: AuditLog) {

    /** ① 이 끝난 환불. [refundAmount] 만 PG 로 간다 */
    data class Requested(
        val refundId: Long,
        val reservationId: Long,
        val paymentId: Long,
        val daysBefore: Int,
        val tierRate: BigDecimal,
        val feeAmount: Int,
        val refundAmount: Int,
    )

    /**
     * ① 조건부 UPDATE 가 먼저다(`D4` — `select` 로 먼저 확인하지 않는다). `reserved` 인 행만 `cancelled` 로 옮기고 관람일과 지금 시각을 같이 받는다.
     * 0행이면 왜인지 다시 읽어 가른다: 없거나 남의 것 → 404, 그 밖의 상태 → 409 `invalid-transition`.
     * 당일이면 그 뒤에 던진다 — 예외가 이 트랜잭션을 통째로 되돌리므로 옮긴 상태도 사라진다.
     */
    @Transactional
    fun request(accountId: Long, reservationId: Long): Requested {
        val cancelled = jdbc.sql(
            """
            update reservation r
               set status = :cancelled, cancelled_at = now()
              from performance p
             where p.performance_id = r.performance_id
               and r.reservation_id = :id and r.account_id = :account and r.status = :reserved
            returning p.starts_at, now() as cancelled_at
            """,
        )
            .param("cancelled", ReservationStatus.CANCELLED.code)
            .param("reserved", ReservationStatus.RESERVED.code)
            .param("id", reservationId)
            .param("account", accountId)
            .query(Cancelled::class.java)
            .optional()
            .orElseThrow { cannotCancel(accountId, reservationId) }

        val daysBefore = RefundPolicy.daysBefore(cancelled.startsAt, cancelled.cancelledAt)
        val rate = tierRateFor(daysBefore)
            ?: throw TicketException(
                ErrorCode.CANCEL_WINDOW_CLOSED,
                "관람일 당일이라 취소할 수 없다: starts_at=${cancelled.startsAt}",
                mapOf("starts_at" to cancelled.startsAt),
            )

        val payment = approvedPaymentOf(reservationId)
        val fee = RefundPolicy.fee(payment.amount, rate)
        val refundId = insertRefund(payment.paymentId, daysBefore, rate, fee, payment.amount - fee)

        jdbc.sql(
            "update performance_seat set status = :available, held_until = null, reservation_id = null where reservation_id = :id and status = :reserved",
        )
            .param("available", PerformanceSeatStatus.AVAILABLE.code)
            .param("reserved", PerformanceSeatStatus.RESERVED.code)
            .param("id", reservationId)
            .update()

        auditLog.record(
            AuditLog.Kind.OUTCOME,
            "reservation.cancelled",
            accountId,
            AuditLog.Target.of("reservation", reservationId),
            mapOf("days_before" to daysBefore, "fee_amount" to fee, "refund_amount" to payment.amount - fee),
        )
        return Requested(refundId, reservationId, payment.paymentId, daysBefore, rate, fee, payment.amount - fee)
    }

    private fun cannotCancel(accountId: Long, reservationId: Long): TicketException {
        val status = jdbc.sql("select status from reservation where reservation_id = :id and account_id = :account")
            .param("id", reservationId)
            .param("account", accountId)
            .query(String::class.java)
            .optional()
            .orElse(null) ?: return TicketException(ErrorCode.RESERVATION_NOT_FOUND)
        return TicketException(ErrorCode.INVALID_TRANSITION, "취소할 수 없는 상태다: $status", mapOf("from" to status, "action" to "cancel"))
    }

    /** ③ 환불이 나갔다. 조건부라 두 번 와도 둘째는 0행이다 */
    @Transactional
    fun complete(refundId: Long, refundNumber: String): Boolean =
        jdbc.sql(
            "update refund set status = :done, refund_number = :number, done_at = now() where refund_id = :id and status = :requested",
        )
            .param("done", "done")
            .param("requested", "requested")
            .param("number", refundNumber)
            .param("id", refundId)
            .update() == 1

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

    /** `reserved` 예매에는 승인된 결제가 정확히 하나 있다(`payment_approved_idx`). 없으면 상태와 결제가 어긋난 것이다 */
    private fun approvedPaymentOf(reservationId: Long): ApprovedPayment =
        jdbc.sql("select payment_id, amount from payment where reservation_id = :id and status = :approved")
            .param("id", reservationId)
            .param("approved", PaymentStatus.APPROVED.code)
            .query(ApprovedPayment::class.java)
            .optional()
            .orElseThrow { IllegalStateException("reserved 예매에 승인된 결제가 없다: reservation_id=$reservationId") }

    private fun insertRefund(paymentId: Long, daysBefore: Int, rate: BigDecimal, fee: Int, refund: Int): Long =
        jdbc.sql(
            """
            insert into refund (payment_id, reason, days_before, tier_rate, fee_amount, refund_amount)
            values (:payment, :reason, :days, :rate, :fee, :refund)
            returning refund_id
            """,
        )
            .param("payment", paymentId)
            .param("reason", REASON_AUDIENCE)
            .param("days", daysBefore)
            .param("rate", rate)
            .param("fee", fee)
            .param("refund", refund)
            .query(Long::class.java)
            .single()

    data class Cancelled(val startsAt: OffsetDateTime, val cancelledAt: OffsetDateTime)

    data class ApprovedPayment(val paymentId: Long, val amount: Int)

    companion object {
        /** `refund.reason` — 관객 취소. `performance_cancelled` 는 17a, `payment_late` 는 17b 가 든다 */
        const val REASON_AUDIENCE = "audience"
    }
}
