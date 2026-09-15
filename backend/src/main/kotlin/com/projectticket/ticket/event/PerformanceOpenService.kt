package com.projectticket.ticket.event

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.settlement.SettlementPolicyQuery
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
class PerformanceOpenService(
    private val jdbc: JdbcClient,
    private val auditLog: AuditLog,
    private val policies: SettlementPolicyQuery,
) {

    @Transactional
    fun open(performanceId: Long, actorAccountId: Long?): Int {
        val performance = load(performanceId)

        if (performance.status != "draft") {
            throw TicketException(ErrorCode.PERFORMANCE_NOT_OPENABLE, "이미 연 회차다: $performanceId")
        }

        verifyEverySectionHasGrade(performance)

        val seatsInHall = seatCountOf(performance.hallId)
        if (seatsInHall == 0) {
            // 좌석이 없는 홀로 회차를 열면 판매 시작과 함께 빈 좌석도가 나간다. 스키마로는 못 막는 조건이라 여기서 막는다.
            throw TicketException(ErrorCode.PERFORMANCE_NOT_OPENABLE, "좌석이 없는 홀이다: hall_id=${performance.hallId}")
        }

        val copied = copySeats(performance)

        // **복제된 수를 홀의 좌석 수와 맞춰 본다.** 위 검사와 복제 사이에 매핑이 지워지거나 좌석이 늘면
        // 내부 조인이 그 좌석을 조용히 빠뜨리고, 증상은 「좌석도에 구멍」이라 판매가 시작된 뒤에야 보인다.
        if (copied != seatsInHall) {
            throw TicketException(
                ErrorCode.PERFORMANCE_NOT_OPENABLE,
                "좌석 복제가 홀의 좌석 수와 다르다: 복제 $copied, 홀 $seatsInHall",
            )
        }

        // **정책을 오픈 때 박제한다**(`D21`). 가격을 박제한 것과 같은 논리다 — 요율을 고쳐도 이미 열린 회차의 정산이 안 흔들린다.
        val policy = policies.currentFor(organizerOf(performance.eventId))
        jdbc.sql("update performance set status = 'open', settlement_policy_id = :policy where performance_id = :id")
            .param("policy", policy.settlementPolicyId)
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

    /**
     * **회차 행을 잠그고 읽는다.** 안 잠그면 동시에 두 번 열 때 둘 다 `draft` 를 보고 지나가서,
     * 진 쪽이 `performance_seat_key` 위반으로 죽는다 — 그것은 마지막 그물에 걸려 **500** 이 되고,
     * 이 자리가 주려던 422 가 안 나간다.
     */
    private fun load(performanceId: Long): Performance =
        jdbc.sql(
            """
            select performance_id, event_id, hall_id, status
              from performance
             where performance_id = :id
               for update
            """,
        )
            .param("id", performanceId)
            .query(Performance::class.java)
            .optional()
            .orElseThrow { TicketException(ErrorCode.PERFORMANCE_NOT_FOUND) }

    private fun organizerOf(eventId: Long): Long =
        jdbc.sql("select organizer_id from event where event_id = :id").param("id", eventId).query(Long::class.java).single()

    private fun seatCountOf(hallId: Long): Int =
        jdbc.sql("select count(*) from seat where hall_id = :hallId")
            .param("hallId", hallId)
            .query(Int::class.java)
            .single()

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
     * `performance_seat_key` 는 **이미 복제된 좌석**만 막는다. 홀에 좌석이 늘어난 뒤의 둘째 호출은 그 유일 제약에 안 걸리므로,
     * 막는 것은 위의 잠근 상태 검사다.
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
