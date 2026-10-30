package com.projectticket.ticket.payment

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.event.PerformanceSeatStatus
import com.projectticket.ticket.event.PerformanceStatus
import com.projectticket.ticket.event.SeatVersions
import com.projectticket.ticket.observability.TicketMetrics
import com.projectticket.ticket.outbox.EventType
import com.projectticket.ticket.outbox.OutboxWriter
import com.projectticket.ticket.reservation.CancelledBy
import com.projectticket.ticket.reservation.ReservationStatus
import com.projectticket.ticket.reservation.TicketService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * 결제의 트랜잭션 둘 — ① `held → paying` ③ 결과 반영. ②(PG 호출)는 [PaymentService] 가 트랜잭션 **밖**에서 한다(`D4` 「트랜잭션 경계」).
 *
 * 둘 다 **조건부 UPDATE** 다. 스윕·타임아웃(`HoldSweeper`)이 같은 행을 두고 다투는 자리라 갱신 행 수가 판정이다 —
 * 승인이 0행이면 좌석을 못 주는 것이고, 그때가 `payment_late` 다.
 */
@Service
class PaymentTransitionService(
    private val jdbc: JdbcClient,
    private val auditLog: AuditLog,
    private val tickets: TicketService,
    private val outbox: OutboxWriter,
    private val seatVersions: SeatVersions,
    private val metrics: TicketMetrics,
) {

    private val log = LoggerFactory.getLogger(PaymentTransitionService::class.java)

    /** ① 이 끝난 예매. 낼 돈은 여기서만 온다 — 클라이언트가 금액을 보내지 않는다 */
    data class Paying(val reservationId: Long, val amount: Int)

    /** ③ 의 결과. [late] 면 승인은 났는데 좌석을 못 줬다 — 감사 `payment.late` 가 남고 환불 행(17b)이 되돌린다 */
    data class Settled(val paymentId: Long, val reservationStatus: String, val late: Boolean)

    /** 사건 페이로드에 드는 것 — 회차와 좌석 수(`D11` 카탈로그) */
    data class ReservedShape(val performanceId: Long, val seatCount: Int)

    /**
     * ① `held → paying`. `paying_until` 을 박제한다(`D7`). **`held_until` 은 안 늘린다** — 결제 시도가 선점을 연장하지 않는다(`D3`).
     *
     * 0행이면 왜인지 다시 읽어 가른다: 없거나 남의 것 → 404, 회차가 판매 중이 아님 → 409 `performance-not-open`,
     * 선점이 지남 → 409 `hold-expired`, 그 밖의 상태 → 409 `invalid-transition`.
     *
     * **회차가 `open` 인지 같은 문장에서 본다**(`I13-1`). 선점이 회차 행을 잠가 취소·종료가 예매를 놓치는 길은 닫혔다 — 이것은 겹 하나 더다(`psql`·새 입구).
     */
    @Transactional
    fun startPaying(accountId: Long, reservationId: Long): Paying {
        val amount = jdbc.sql(
            """
            update reservation
               set status = :paying, paying_until = now() + make_interval(mins => :minutes)
             where reservation_id = :id and account_id = :account
               and status = :held and held_until >= now()
               and exists (select 1 from performance p where p.performance_id = reservation.performance_id and p.status = :open)
            returning total_amount
            """,
        )
            .param("paying", ReservationStatus.PAYING.code)
            .param("held", ReservationStatus.HELD.code)
            .param("open", PerformanceStatus.OPEN.code)
            .param("minutes", PAYING_MINUTES)
            .param("id", reservationId)
            .param("account", accountId)
            .query(Int::class.java)
            .optional()
            .orElseThrow { cannotStart(accountId, reservationId) }

        auditLog.record(
            kind = AuditLog.Kind.OUTCOME,
            eventType = "reservation.paying",
            actorAccountId = accountId,
            target = AuditLog.Target.of("reservation", reservationId),
        )
        return Paying(reservationId, amount)
    }

    private fun cannotStart(accountId: Long, reservationId: Long): TicketException {
        val row = jdbc.sql(
            """
            select r.status, r.held_until >= now() as alive, p.status as performance_status
              from reservation r join performance p on p.performance_id = r.performance_id
             where r.reservation_id = :id and r.account_id = :account
            """,
        )
            .param("id", reservationId)
            .param("account", accountId)
            .query { rs, _ -> StartRefusal(rs.getString("status"), rs.getBoolean("alive"), rs.getString("performance_status")) }
            .optional()
            .orElse(null)
            ?: return TicketException(ErrorCode.RESERVATION_NOT_FOUND)

        val (status, alive, performanceStatus) = row
        val held = status == ReservationStatus.HELD.code
        return when {
            held && performanceStatus != PerformanceStatus.OPEN.code -> TicketException(
                ErrorCode.PERFORMANCE_NOT_OPEN,
                "판매 중인 회차가 아니다: reservation_id=$reservationId, status=$performanceStatus",
                mapOf("performance_status" to performanceStatus),
            )
            held && !alive -> TicketException(ErrorCode.HOLD_EXPIRED, "선점 시간이 지났다: reservation_id=$reservationId")
            else -> TicketException(ErrorCode.INVALID_TRANSITION, "결제할 수 없는 상태다: $status", mapOf("from" to status, "action" to "pay"))
        }
    }

    /**
     * ③ 결과 반영. **멱등 트랜잭션 안에서 부른다** — 결제 행과 예매 상태가 한 트랜잭션이어야 「승인은 적혔는데 예매는 `paying`」이 안 남는다.
     *
     * 거절·실패는 예외가 아니라 결과다. 예외로 던지면 결제 행이 롤백돼 `declined`·`failed` 가 코드에서 안 쓰이고, 재전송이 PG 를 또 부른다.
     */
    @Transactional
    // 결제사 무응답(시간 초과)이 `verdict == null` 이다 — 아래 분기가 FAILED 로 받는다. detekt 는 기본값의 `requireNotNull(verdict)` 를 보고 늘 non-null 로 읽는다
    @Suppress("CanBeNonNullable")
    fun settle(
        accountId: Long,
        paying: Paying,
        verdict: MockPaymentGateway.Result?,
        // 응답이 없으면 결과에 카드가 없다. 요청이 든 뒷 4자리를 호출자가 준다 — `0000` 같은 자리표시는 거절 카드와 겹친다.
        cardLast4: String = requireNotNull(verdict) { "응답이 없으면 cardLast4 를 줘야 한다" }.cardLast4,
    ): Settled {
        val status = when {
            verdict == null -> PaymentStatus.FAILED
            verdict.approved -> PaymentStatus.APPROVED
            else -> PaymentStatus.DECLINED
        }
        val paymentId = insertPayment(paying = paying, status = status, verdict = verdict, cardLast4 = cardLast4)

        val reservationStatus = if (status == PaymentStatus.APPROVED) {
            confirm(accountId, paying.reservationId)
        } else {
            returnToHeld(accountId, paying.reservationId)
        }

        val late = status == PaymentStatus.APPROVED && reservationStatus != ReservationStatus.RESERVED.code
        if (status == PaymentStatus.APPROVED && !late) {
            // 확정과 **같은 트랜잭션**에서 사건을 커밋한다(`D11`). 좌석을 못 준 승인(late)은 예매가 성립 안 했으니 사건도 없다 — 되돌리는 것은 환불(17b)이다.
            val shape = jdbc.sql(
                """
                select r.performance_id, (select count(*) from reservation_seat rs where rs.reservation_id = r.reservation_id) as seat_count
                  from reservation r where r.reservation_id = :id
                """,
            ).param("id", paying.reservationId).query(ReservedShape::class.java).single()
            outbox.append(
                EventType.RESERVATION_RESERVED,
                paying.reservationId,
                mapOf(
                    "reservation_id" to paying.reservationId,
                    "account_id" to accountId,
                    "performance_id" to shape.performanceId,
                    "payment_id" to paymentId,
                    "total_amount" to paying.amount,
                    "seat_count" to shape.seatCount,
                ),
            )
        }
        if (late) {
            // 스윕·타임아웃이 먼저 갔다. 돈은 받았고 좌석은 없다 — 사람이 볼 것이라 WARN 이고, 되돌리는 것은 환불 행(17b)이다.
            log.warn("승인이 늦어 좌석을 못 줬다 reservation_id={} payment_id={}", paying.reservationId, paymentId)
            auditLog.record(
                kind = AuditLog.Kind.OUTCOME,
                eventType = "payment.late",
                actorAccountId = accountId,
                target = AuditLog.Target.of("payment", paymentId),
            )
        }
        return Settled(paymentId, reservationStatus, late)
    }

    private fun insertPayment(paying: Paying, status: PaymentStatus, verdict: MockPaymentGateway.Result?, cardLast4: String): Long =
        jdbc.sql(
            """
            insert into payment (reservation_id, status, amount, approval_number, decline_reason, card_last4)
            values (:reservation, :status, :amount, :approval, :reason, :last4)
            returning payment_id
            """,
        )
            .param("reservation", paying.reservationId)
            .param("status", status.code)
            .param("amount", paying.amount)
            .param("approval", verdict?.approvalNumber)
            .param("reason", if (verdict == null) NO_RESPONSE else verdict.declineReason)
            .param("last4", cardLast4)
            .query(Long::class.java)
            .single()

    /**
     * 승인 반영. **`paying_until` 이 안 지났을 때만** — 지났으면 타임아웃이 곧 (또는 이미) `expired` 로 옮긴다(`D4` 「스윕과 확정의 경합」).
     * 0행이면 이 예매의 지금 상태를 그대로 돌려준다.
     */
    private fun confirm(accountId: Long, reservationId: Long): String {
        val confirmed = jdbc.sql(
            """
            update reservation
               set status = :reserved, reserved_at = now(), paying_until = null
             where reservation_id = :id and status = :paying and paying_until >= now()
               and exists (select 1 from performance p where p.performance_id = reservation.performance_id and p.status = :open)
            """,
        )
            .param("reserved", ReservationStatus.RESERVED.code)
            .param("paying", ReservationStatus.PAYING.code)
            .param("open", PerformanceStatus.OPEN.code)
            .param("id", reservationId)
            .update()
        if (confirmed == 0) return statusOf(reservationId)

        val confirmedSeats = jdbc.sql(
            """
            update performance_seat set status = :reserved, held_until = null
             where reservation_id = :id and status = :held
            returning performance_seat_id, performance_id
            """,
        )
            .param("reserved", PerformanceSeatStatus.RESERVED.code)
            .param("held", PerformanceSeatStatus.HELD.code)
            .param("id", reservationId)
            .query(SeatVersions.Row::class.java)
            .list()
            .filterNotNull()
        // 커밋 뒤에 판이 오른다(`D20`). 커밋 전에 올리면 롤백된 확정이 화면에 팔린 자리로 보인다.
        seatVersions.publishAfterCommit(confirmedSeats, PerformanceSeatStatus.RESERVED)
        metrics.reservationConfirmed()
        // 확정과 발권이 한 트랜잭션이다(18). 발권일은 DB 시각이다(`D7`).
        val issued = tickets.issue(reservationId, jdbc.sql("select now()").query(OffsetDateTime::class.java).single())
        auditLog.record(
            kind = AuditLog.Kind.OUTCOME,
            eventType = "reservation.reserved",
            actorAccountId = accountId,
            target = AuditLog.Target.of("reservation", reservationId),
            detail = mapOf("tickets_issued" to issued),
        )
        log.info("예매 확정 reservation_id={} tickets={}", reservationId, issued)
        return ReservationStatus.RESERVED.code
    }

    /**
     * 거절·실패 → `held`. 선점 시간이 남았을 때만이다 — 다른 카드로 다시 시도하는 자리(`D3` 「`paying → held` 는 재시도 허용」).
     * 선점까지 지났으면 `expired` 로 보내고 좌석을 돌려준다(`D3` 전이표).
     */
    private fun returnToHeld(accountId: Long, reservationId: Long): String {
        val backToHeld = jdbc.sql(
            """
            update reservation
               set status = :held, paying_until = null
             where reservation_id = :id and status = :paying and held_until >= now()
            """,
        )
            .param("held", ReservationStatus.HELD.code)
            .param("paying", ReservationStatus.PAYING.code)
            .param("id", reservationId)
            .update()
        if (backToHeld == 1) {
            // 선점(`SeatHoldService`)과 같은 이름이다 — `from` 이 둘을 가른다(`D3` 「상태 이력」, `I11-11`).
            auditLog.record(
                kind = AuditLog.Kind.OUTCOME,
                eventType = "reservation.held",
                actorAccountId = accountId,
                target = AuditLog.Target.of("reservation", reservationId),
                detail = mapOf("from" to ReservationStatus.PAYING.code),
            )
            return ReservationStatus.HELD.code
        }

        val expired = jdbc.sql(
            """
            update reservation
               set status = :expired, expired_at = now(), paying_until = null, cancelled_by = :by
             where reservation_id = :id and status = :paying
            """,
        )
            .param("expired", ReservationStatus.EXPIRED.code)
            .param("by", CancelledBy.EXPIRED.code)
            .param("paying", ReservationStatus.PAYING.code)
            .param("id", reservationId)
            .update()
        if (expired == 0) return statusOf(reservationId)

        val released = jdbc.sql(
            """
            update performance_seat set status = :available, held_until = null, reservation_id = null
             where reservation_id = :id and status = :held
            returning performance_seat_id, performance_id
            """,
        )
            .param("available", PerformanceSeatStatus.AVAILABLE.code)
            .param("held", PerformanceSeatStatus.HELD.code)
            .param("id", reservationId)
            .query(SeatVersions.Row::class.java)
            .list()
            .filterNotNull()
        seatVersions.publishAfterCommit(released, PerformanceSeatStatus.AVAILABLE)
        auditLog.record(
            kind = AuditLog.Kind.OUTCOME,
            eventType = "reservation.expired",
            actorAccountId = accountId,
            target = AuditLog.Target.of("reservation", reservationId),
        )
        return ReservationStatus.EXPIRED.code
    }

    private data class StartRefusal(val status: String, val alive: Boolean, val performanceStatus: String)

    private fun statusOf(reservationId: Long): String =
        jdbc.sql("select status from reservation where reservation_id = :id").param("id", reservationId).query(String::class.java).single()

    companion object {
        /** ADR 0004 — 결제 중 3분. 넘기면 타임아웃이 `expired` 로 보낸다 */
        const val PAYING_MINUTES = 3

        /** `payment.decline_reason` 에 적는 실패 사유 — 응답이 없었고 PG 도 모른다 */
        const val NO_RESPONSE = "no_response"
    }
}
