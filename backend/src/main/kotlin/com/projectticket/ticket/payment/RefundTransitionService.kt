package com.projectticket.ticket.payment

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.event.PerformanceSeatStatus
import com.projectticket.ticket.event.SeatVersions
import com.projectticket.ticket.observability.TicketMetrics
import com.projectticket.ticket.outbox.EventType
import com.projectticket.ticket.outbox.OutboxWriter
import com.projectticket.ticket.reservation.CancelledBy
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
 * 수수료 계산은 **① 안에서, DB 의 `now()` 로** 한다(`D7` 「시계는 DB 다」). 계산은 [RefundQuote.amounts] 한 곳이다 — 미리보기(`44a`)와 같은 함수다.
 */
@Service
class RefundTransitionService(
    private val jdbc: JdbcClient,
    private val auditLog: AuditLog,
    private val outbox: OutboxWriter,
    private val seatVersions: SeatVersions,
    private val metrics: TicketMetrics,
    private val quote: RefundQuote,
) {

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
     * 당일이면 그 뒤에 던진다 — 예외가 이 트랜잭션을 통째로 되돌리므로 옮긴 상태도 사라진다. 본 금액이 다를 때(`44a-1a`)도 같은 자리다.
     *
     * @param expectedRefund 화면이 본 환불액. `null` 이면 대조를 건너뛴다 — 입구([RefundService])는 늘 싣고, `null` 은 이 함수를 직접 부르는 시험 몫이다.
     */
    @Transactional
    fun request(accountId: Long, reservationId: Long, expectedRefund: Int? = null): Requested {
        val cancelled = jdbc.sql(
            """
            update reservation r
               set status = :cancelled, cancelled_at = now(), cancelled_by = :by
              from performance p
             where p.performance_id = r.performance_id
               and r.reservation_id = :id and r.account_id = :account and r.status = :reserved
            returning p.performance_id, p.starts_at, now() as cancelled_at
            """,
        )
            .param("cancelled", ReservationStatus.CANCELLED.code)
            .param("by", CancelledBy.AUDIENCE.code)
            .param("reserved", ReservationStatus.RESERVED.code)
            .param("id", reservationId)
            .param("account", accountId)
            .query(Cancelled::class.java)
            .optional()
            .orElseThrow { cannotCancel(accountId, reservationId) }

        val payment = approvedPaymentOf(reservationId)
        val amounts = quote.amounts(cancelled.startsAt, cancelled.cancelledAt, payment.amount)
            ?: throw TicketException(
                ErrorCode.CANCEL_WINDOW_CLOSED,
                "관람일 당일이라 취소할 수 없다: starts_at=${cancelled.startsAt}",
                mapOf("starts_at" to cancelled.startsAt),
            )
        if (expectedRefund != null && amounts.refundAmount != expectedRefund) {
            throw TicketException(
                ErrorCode.QUOTE_CHANGED,
                "본 금액과 지금 금액이 다르다: 본 금액=$expectedRefund, 지금=${amounts.refundAmount}",
                mapOf("refund_amount" to amounts.refundAmount),
            )
        }

        val daysBefore = amounts.daysBefore
        val rate = amounts.tierRate
        val fee = amounts.feeAmount
        val refundAmount = amounts.refundAmount
        val refundId = insertRefund(payment.paymentId, daysBefore, rate, fee, refundAmount)

        val released = jdbc.sql(
            """
            update performance_seat set status = :available, held_until = null, reservation_id = null
             where reservation_id = :id and status = :reserved
            returning performance_seat_id, performance_id
            """,
        )
            .param("available", PerformanceSeatStatus.AVAILABLE.code)
            .param("reserved", PerformanceSeatStatus.RESERVED.code)
            .param("id", reservationId)
            .query(SeatVersions.Row::class.java)
            .list()
            .filterNotNull()
        seatVersions.publishAfterCommit(released, PerformanceSeatStatus.AVAILABLE)
        metrics.reservationCancelled(CancelledBy.AUDIENCE.code)

        // 취소 트랜잭션이 사건을 같이 커밋한다(`D11`). 감사와 이름이 같지만 목적이 다르다 — 이쪽은 소비자가 반응하려고 있는 계약이다.
        outbox.append(
            EventType.RESERVATION_CANCELLED,
            reservationId,
            mapOf(
                "reservation_id" to reservationId,
                "account_id" to accountId,
                "performance_id" to cancelled.performanceId,
                "refund_id" to refundId,
                "reason" to REASON_AUDIENCE,
                "fee_amount" to fee,
                "refund_amount" to refundAmount,
            ),
        )

        auditLog.record(
            AuditLog.Kind.OUTCOME,
            "reservation.cancelled",
            accountId,
            AuditLog.Target.of("reservation", reservationId),
            mapOf("days_before" to daysBefore, "fee_amount" to fee, "refund_amount" to refundAmount),
        )
        return Requested(refundId, reservationId, payment.paymentId, daysBefore, rate, fee, refundAmount)
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

    /**
     * 승인이 늦어 좌석을 못 준 결제에 전액 환불 행을 만든다(`17b` ⓑ).
     *
     * 16 은 PG 취소만 보내고 **행을 안 만들었다** — 돈은 돌아갔는데 우리 표에는 그 사실이 없어서
     * 정산(27)과 조회가 「승인된 결제인데 예매가 없다」를 설명하지 못한다.
     *
     * 관객 잘못이 아니라 전액이다(`D6`) — 율 0·수수료 0. 스윕이 이 행을 PG 로 보낸다.
     *
     * **`not exists` 로 멱등이다.** 두 번 돌아도 둘째는 0행이고, 뚫려도 결제당 하나라는 유일 제약이 받는다.
     */
    @Transactional
    fun queueLatePayments(): List<Long> =
        jdbc.sql(
            """
            insert into refund (payment_id, reason, days_before, tier_rate, fee_amount, refund_amount)
            select p.payment_id, :reason, 0, 0, 0, p.amount
              from payment p
              join reservation r on r.reservation_id = p.reservation_id
             where p.status = :approved
               and r.status = :expired
               and not exists (select 1 from refund rf where rf.payment_id = p.payment_id)
            returning refund_id
            """,
        )
            .param("reason", REASON_PAYMENT_LATE)
            .param("approved", PaymentStatus.APPROVED.code)
            .param("expired", ReservationStatus.EXPIRED.code)
            .query(Long::class.java)
            .list()
            .filterNotNull()

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

    data class Cancelled(val performanceId: Long, val startsAt: OffsetDateTime, val cancelledAt: OffsetDateTime)

    data class ApprovedPayment(val paymentId: Long, val amount: Int)

    companion object {
        /** `refund.reason` — 승인이 늦어 좌석을 못 준 결제(`17b` ⓑ). 관객 잘못이 아니라 전액이다 */
        const val REASON_PAYMENT_LATE = "payment_late"

        /** `refund.reason` — 관객 취소. `performance_cancelled` 는 17a, `payment_late` 는 17b 가 든다 */
        const val REASON_AUDIENCE = "audience"
    }
}
