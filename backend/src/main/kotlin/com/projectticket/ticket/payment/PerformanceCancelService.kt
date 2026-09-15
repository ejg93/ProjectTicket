package com.projectticket.ticket.payment

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.event.PerformanceSeatStatus
import com.projectticket.ticket.outbox.EventType
import com.projectticket.ticket.outbox.OutboxWriter
import com.projectticket.ticket.reservation.ReservationStatus
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 기획사가 회차를 취소한다(17a). **판 것까지 무르는 유일한 자리**다 — 자동 종료(16a)가 `reserved` 를 안 건드리는 것과 반대다.
 *
 * **전부 한 트랜잭션이다.** 회차·예매·좌석·환불 행·사건이 같이 커밋된다 — 갈라지면 「취소됐는데 환불이 없는 예매」가 남고,
 * 그것은 돈을 안 돌려준 채 좌석만 회수한 상태다.
 *
 * **PG 는 여기서 안 부른다**(사용자 선택). 확정 예매가 수백 건이면 호출도 수백 번이고 `D4` 「바깥 호출은 트랜잭션 밖」에 걸린다 —
 * 환불 행을 `requested` 로 남기고 [RefundSweeper] 가 트랜잭션 밖에서 보낸다.
 *
 * `payment` 패키지인 이유는 의존 방향이다(`D14`) — 환불을 만지므로 `reservation` 에 두면 역방향이다. `RefundController` 와 같은 판단.
 */
@Service
class PerformanceCancelService(
    private val jdbc: JdbcClient,
    private val auditLog: AuditLog,
    private val outbox: OutboxWriter,
) {

    private val log = LoggerFactory.getLogger(PerformanceCancelService::class.java)

    /** @return 취소된 예매 수 */
    @Transactional
    fun cancel(performanceId: Long, actorAccountId: Long?): Int {
        // 조건부 UPDATE. `open` 이 아니면 0행이고, 그때 왜인지 다시 읽어 가른다.
        val cancelled = jdbc.sql("update performance set status = 'cancelled' where performance_id = :id and status = 'open'")
            .param("id", performanceId)
            .update()
        if (cancelled == 0) throw cannotCancel(performanceId)

        // 살아있는 예매 전부 — `held`·`paying` 은 돈이 안 움직였고 `reserved` 는 환불이 따라온다.
        val affected = jdbc.sql(
            """
            update reservation
               set status = :cancelled, cancelled_at = now(), paying_until = null
             where performance_id = :id and status in (:live)
            returning reservation_id, status
            """,
        )
            .param("cancelled", ReservationStatus.CANCELLED.code)
            .param("id", performanceId)
            .param("live", LIVE_STATUSES)
            .query { rs, _ -> rs.getLong("reservation_id") }
            .list()
            .filterNotNull()

        if (affected.isNotEmpty()) {
            releaseSeats(performanceId)
            refundInFull(performanceId)
            affected.forEach {
                auditLog.record(AuditLog.Kind.OUTCOME, "reservation.cancelled", actorAccountId, AuditLog.Target.of("reservation", it))
            }
        }

        // 수신자가 여럿인 유일한 사건이다(`D11`) — 소비자가 예매자를 표에서 읽는다.
        outbox.append(
            EventType.PERFORMANCE_CANCELLED,
            performanceId,
            mapOf("performance_id" to performanceId, "cancelled_reservation_count" to affected.size),
        )
        auditLog.record(
            AuditLog.Kind.OUTCOME,
            "performance.cancelled",
            actorAccountId,
            AuditLog.Target.of("performance", performanceId),
            mapOf("cancelled_reservation_count" to affected.size),
        )
        log.info("회차 취소 performance_id={} 예매={}건", performanceId, affected.size)
        return affected.size
    }

    private fun cannotCancel(performanceId: Long): TicketException {
        val status = jdbc.sql("select status from performance where performance_id = :id")
            .param("id", performanceId).query(String::class.java).optional().orElse(null)
            ?: return TicketException(ErrorCode.PERFORMANCE_NOT_FOUND)
        return TicketException(
            ErrorCode.INVALID_TRANSITION,
            "취소할 수 없는 회차다: $status",
            mapOf("from" to status, "action" to "cancel"),
        )
    }

    private fun releaseSeats(performanceId: Long) =
        jdbc.sql(
            """
            update performance_seat
               set status = :available, held_until = null, reservation_id = null
             where performance_id = :id and status in (:taken)
            """,
        )
            .param("available", PerformanceSeatStatus.AVAILABLE.code)
            .param("id", performanceId)
            .param("taken", listOf(PerformanceSeatStatus.HELD.code, PerformanceSeatStatus.RESERVED.code))
            .update()

    /**
     * 확정된 예매의 승인 결제에 **전액 환불 행**을 만든다(`D6` 「사유별」 — 구간과 무관하게 0%).
     *
     * `days_before` 는 0 을 넣는다 — 관객이 고른 시점이 아니라 회차가 사라진 것이라 구간을 안 탄다.
     * `refund_full_for_non_audience_check`(`V10`)가 `reason <> 'audience'` 면 율·수수료가 0 이어야 한다고 이미 막는다.
     *
     * 이미 환불된 결제(관객이 먼저 취소)는 `on conflict do nothing` 으로 건너뛴다 — 결제당 환불은 하나다.
     */
    private fun refundInFull(performanceId: Long) =
        jdbc.sql(
            """
            insert into refund (payment_id, reason, days_before, tier_rate, fee_amount, refund_amount)
            select pm.payment_id, :reason, 0, 0, 0, pm.amount
              from payment pm join reservation r on r.reservation_id = pm.reservation_id
             where r.performance_id = :id and pm.status = :approved
            on conflict (payment_id) do nothing
            """,
        )
            .param("reason", REASON_PERFORMANCE_CANCELLED)
            .param("id", performanceId)
            .param("approved", PaymentStatus.APPROVED.code)
            .update()

    companion object {
        /** `refund.reason` — 회차 취소. 관객 잘못이 아니라 전액이다(`D6`) */
        const val REASON_PERFORMANCE_CANCELLED = "performance_cancelled"

        private val LIVE_STATUSES = listOf(
            ReservationStatus.HELD.code,
            ReservationStatus.PAYING.code,
            ReservationStatus.RESERVED.code,
        )
    }
}
