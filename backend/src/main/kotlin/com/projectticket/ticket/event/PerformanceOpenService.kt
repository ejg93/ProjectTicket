package com.projectticket.ticket.event

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 회차를 연다. 홀의 좌석을 회차별 좌석으로 복제하고 상태를 `open` 으로 바꾼다.
 *
 * **한 트랜잭션이다.** 복제만 되고 상태가 안 바뀌면 아무도 못 사는 좌석 수천 행이 남고,
 * 상태만 바뀌고 복제가 안 되면 **좌석이 하나도 없는 열린 회차**가 된다.
 */
@Service
class PerformanceOpenService(private val jdbc: JdbcClient, private val auditLog: AuditLog) {

    @Transactional
    fun open(performanceId: Long, actorAccountId: Long?): Int {
        val performance = load(performanceId)

        if (performance.status != "draft") {
            throw TicketException(ErrorCode.PERFORMANCE_NOT_OPENABLE, "이미 연 회차다: $performanceId")
        }

        verifyEverySectionHasGrade(performance)
        val copied = copySeats(performance)

        if (copied == 0) {
            // 좌석이 없는 홀로 회차를 열면 판매 시작과 함께 빈 좌석도가 나간다. 스키마로는 못 막는 조건이라 여기서 막는다.
            throw TicketException(ErrorCode.PERFORMANCE_NOT_OPENABLE, "좌석이 없는 홀이다: hall_id=${performance.hallId}")
        }

        jdbc.sql("update performance set status = 'open' where performance_id = :id")
            .param("id", performanceId)
            .update()

        auditLog.record(
            AuditLog.Kind.OUTCOME,
            "performance.opened",
            actorAccountId,
            AuditLog.Target.of("performance", performanceId),
            mapOf("seat_count" to copied),
        )
        return copied
    }

    private fun load(performanceId: Long): Performance =
        jdbc.sql("select performance_id, event_id, hall_id, status from performance where performance_id = :id")
            .param("id", performanceId)
            .query(Performance::class.java)
            .optional()
            .orElseThrow { TicketException(ErrorCode.PERFORMANCE_NOT_FOUND) }

    /**
     * 등급이 안 붙은 구역이 있으면 연다는 것 자체가 성립하지 않는다.
     *
     * **스키마로 못 막는다.** 매핑은 공연 단위고 어느 홀을 쓰는지는 회차가 정하므로, 덮였는지는 이 둘이 만나는 순간에야 안다.
     * 여기서 안 막으면 복제가 그 구역만 조용히 빠뜨리고 **좌석도에 구멍이 뚫린 채로 판매가 시작된다.**
     */
    private fun verifyEverySectionHasGrade(performance: Performance) {
        val uncovered = jdbc.sql(
            """
            select distinct s.section
              from seat s
             where s.hall_id = :hallId
               and not exists (
                   select 1 from seat_grade_map m
                    where m.event_id = :eventId and m.section = s.section)
             order by s.section
            """,
        )
            .param("hallId", performance.hallId)
            .param("eventId", performance.eventId)
            .query(String::class.java)
            .list()
            .filterNotNull()

        if (uncovered.isNotEmpty()) {
            throw TicketException(
                ErrorCode.PERFORMANCE_NOT_OPENABLE,
                "등급이 안 붙은 구역이 있다: ${uncovered.joinToString()}",
            )
        }
    }

    /**
     * 좌석을 한 문장으로 복제한다. 행마다 왕복하면 2천 석에 2천 번이고, 그 사이에 트랜잭션이 열려 있다.
     *
     * 두 번 부르면 `performance_seat_key` 가 막는다 — 위 상태 검사보다 낮은 자리라 경쟁해서 들어와도 걸린다.
     */
    private fun copySeats(performance: Performance): Int =
        jdbc.sql(
            """
            insert into performance_seat (performance_id, seat_id, seat_grade_id, price)
            select :performanceId, s.seat_id, g.seat_grade_id, g.price
              from seat s
              join seat_grade_map m on m.event_id = :eventId and m.section = s.section
              join seat_grade g on g.seat_grade_id = m.seat_grade_id
             where s.hall_id = :hallId
            """,
        )
            .param("performanceId", performance.performanceId)
            .param("eventId", performance.eventId)
            .param("hallId", performance.hallId)
            .update()

    data class Performance(val performanceId: Long, val eventId: Long, val hallId: Long, val status: String)
}
