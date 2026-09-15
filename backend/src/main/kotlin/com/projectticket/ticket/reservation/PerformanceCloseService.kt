package com.projectticket.ticket.reservation

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.event.PerformanceSeatStatus
import com.projectticket.ticket.outbox.EventType
import com.projectticket.ticket.outbox.OutboxWriter
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * 회차 하나를 닫는 트랜잭션(16a). 스케줄러는 [PerformanceCloser] 고, **빈이 갈린 이유는 트랜잭션 경계다** —
 * 같은 클래스 안에서 부르면 프록시를 안 지나 `@Transactional` 이 통째로 무시된다(`stack.md`).
 *
 * 회차마다 따로 커밋한다(사용자 선택). 한 회차가 계속 실패해도 나머지 기획사의 정산이 안 막힌다 — 대가는 이 빈 하나다.
 *
 * `reserved` 는 안 건드린다. 닫는 것은 **파는 일**이지 판 것을 무르는 일이 아니다 — 그쪽은 회차 취소(17a)고 전액 환불이 따라온다.
 *
 * **`event` 가 아니라 여기 있다.** 의존 방향이 `payment → reservation → event` 라(`D14` 「패키지」), 회차를 닫으면서 예매를 만료시키는 이 서비스가
 * `event` 에 있으면 순환이다 — `ArchitectureTest.noPackageCycles` 가 실제로 잡았다. 취소 입구를 `payment` 에 둔 것과 같은 판단이다.
 */
@Service
class PerformanceCloseService(
    private val jdbc: JdbcClient,
    private val auditLog: AuditLog,
    private val outbox: OutboxWriter,
) {

    /** @return 닫았으면 만료시킨 예매 수, 남이 먼저 닫았거나 아직 마감 전이면 null */
    @Transactional
    fun close(performanceId: Long): Int? {
        // 조건부 UPDATE 다. 두 대가 같이 돌아도 둘째는 0행이고, 그 사이 기획사가 취소(17a)했으면 상태가 달라 안 걸린다.
        val closedAt = jdbc.sql(
            """
            update performance
               set status = 'closed'
             where performance_id = :id and status = 'open' and sales_close_at < now()
            returning now() as closed_at
            """,
        )
            .param("id", performanceId)
            .query(OffsetDateTime::class.java)
            .optional()
            .orElse(null) ?: return null

        val expired = jdbc.sql(
            """
            update reservation
               set status = :expired, expired_at = now(), paying_until = null
             where performance_id = :id and status in (:live)
            returning reservation_id
            """,
        )
            .param("expired", ReservationStatus.EXPIRED.code)
            .param("id", performanceId)
            .param("live", LIVE_STATUSES)
            .query(Long::class.java)
            .list()
            .filterNotNull()

        if (expired.isNotEmpty()) {
            jdbc.sql(
                """
                update performance_seat
                   set status = :available, held_until = null, reservation_id = null
                 where status = :held and reservation_id in (:reservations)
                """,
            )
                .param("available", PerformanceSeatStatus.AVAILABLE.code)
                .param("held", PerformanceSeatStatus.HELD.code)
                .param("reservations", expired)
                .update()

            expired.forEach {
                auditLog.record(AuditLog.Kind.OUTCOME, "reservation.expired", null, AuditLog.Target.of("reservation", it))
            }
        }

        // 정산(27)이 이 사건을 받아 `payout_delay_days` 뒤에 정산서를 만든다(`D21`). 종료와 같은 트랜잭션이라 닫혔는데 사건이 없는 회차가 없다.
        outbox.append(
            EventType.PERFORMANCE_CLOSED,
            performanceId,
            mapOf("performance_id" to performanceId, "closed_at" to closedAt.toString()),
        )
        auditLog.record(
            AuditLog.Kind.OUTCOME,
            "performance.closed",
            null,
            AuditLog.Target.of("performance", performanceId),
            mapOf("expired_reservation_count" to expired.size),
        )
        return expired.size
    }

    companion object {
        /** 닫을 때 만료시키는 상태. `reserved` 는 안 건드린다 */
        private val LIVE_STATUSES = listOf(ReservationStatus.HELD.code, ReservationStatus.PAYING.code)
    }
}
