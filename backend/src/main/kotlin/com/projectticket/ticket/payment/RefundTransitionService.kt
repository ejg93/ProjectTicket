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
     * ① 예매 행을 잠그고 읽는다 — 안 잠그면 동시에 두 번 취소할 때 둘 다 `reserved` 를 보고 환불 행을 둘 만들려다 진 쪽이 `refund_payment_id_key` 로 500 이 된다.
     * 잠근 뒤의 상태 갱신은 그래도 조건부다(`D14` 「좌석 상태를 바꾸는 것은 조건부 UPDATE」).
     */
    @Transactional
    fun request(accountId: Long, reservationId: Long): Requested {
        val target = jdbc.sql(
            """
            select r.reservation_id, r.status, p.starts_at, now() as cancelled_at
              from reservation r
              join performance p on p.performance_id = r.performance_id
             where r.reservation_id = :id and r.account_id = :account
               for update of r
            """,
        )
            .param("id", reservationId)
            .param("account", accountId)
            .query(Target::class.java)
            .optional()
            .orElseThrow { TicketException(ErrorCode.RESERVATION_NOT_FOUND) }

        if (target.status != ReservationStatus.RESERVED.code) {
            throw TicketException(
                ErrorCode.INVALID_TRANSITION,
                "취소할 수 없는 상태다: ${target.status}",
                mapOf("from" to target.status, "action" to "cancel"),
            )
        }

        val daysBefore = RefundPolicy.daysBefore(target.startsAt, target.cancelledAt)
        val rate = tierRateFor(daysBefore)
            ?: throw TicketException(
                ErrorCode.CANCEL_WINDOW_CLOSED,
                "관람일 당일이라 취소할 수 없다: starts_at=${target.startsAt}",
                mapOf("starts_at" to target.startsAt),
            )

        val payment = approvedPaymentOf(reservationId)
        val fee = RefundPolicy.fee(payment.amount, rate)
        val refundId = insertRefund(payment.paymentId, daysBefore, rate, fee, payment.amount - fee)

        val cancelled = jdbc.sql(
            "update reservation set status = :cancelled, cancelled_at = now() where reservation_id = :id and status = :reserved",
        )
            .param("cancelled", ReservationStatus.CANCELLED.code)
            .param("reserved", ReservationStatus.RESERVED.code)
            .param("id", reservationId)
            .update()
        check(cancelled == 1) { "잠근 예매가 사이에 바뀌었다: reservation_id=$reservationId" }

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

    data class Target(val reservationId: Long, val status: String, val startsAt: OffsetDateTime, val cancelledAt: OffsetDateTime)

    data class ApprovedPayment(val paymentId: Long, val amount: Int)

    companion object {
        /** `refund.reason` — 관객 취소. 회차 취소(17a)·승인 지연은 그 청크가 자기 값을 든다 */
        const val REASON_AUDIENCE = "audience"
    }
}
