package com.projectticket.ticket.settlement

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * 기획사가 회차 하나의 정산을 읽는다(`45c`, `settlement-rules.md`). 정산서는 회차당 하나다(`settlement_performance_id_key`).
 *
 * **소속 판정을 SQL 조인으로 한다** — `settlement` 는 의존 방향의 맨 아래라(`D14`) `event.OrganizerMembership` 을 부를 수 없다
 * (`ArchitectureTest.resourcePackagesDependDownward`). 없는 회차·남의 회차·정산서가 아직 없는 회차가 **한 이름 404** 다 — 남의 회차 존재를 안 흘린다.
 * 합계는 항목의 합과 같다 — 커밋 시점의 지연 제약 트리거가 든다(`V16`).
 */
@Component
class SettlementQuery(private val jdbc: JdbcClient) {

    fun forPerformance(accountId: Long, performanceId: Long): Settlement {
        val head = jdbc.sql(
            """
            select s.settlement_id, s.performance_id, s.status, s.amount, s.settle_at, s.settled_at
              from settlement s
              join performance p on p.performance_id = s.performance_id
              join event e on e.event_id = p.event_id
              join organizer_member m on m.organizer_id = e.organizer_id and m.account_id = :account
             where s.performance_id = :performance
            """,
        )
            .param("account", accountId)
            .param("performance", performanceId)
            .query(Head::class.java)
            .optional()
            .orElseThrow { TicketException(ErrorCode.PERFORMANCE_NOT_FOUND, "정산서를 읽을 회차가 없다: performance_id=$performanceId") }

        val lines = jdbc.sql("select kind, amount from settlement_line where settlement_id = :id order by settlement_line_id")
            .param("id", head.settlementId)
            .query(Line::class.java)
            .list()
            .filterNotNull()

        return Settlement(head.performanceId, head.status, head.amount, head.settleAt, head.settledAt, lines)
    }

    data class Settlement(
        val performanceId: Long,
        /** `scheduled`·`pending`·`confirmed`·`paid`(`V16`) */
        val status: String,
        val amount: Int,
        val settleAt: OffsetDateTime,
        val settledAt: OffsetDateTime?,
        /** `sale`(+)·`platform_fee`(−)·`cancel_fee`(+)·`adjustment`(±). 합이 [amount] 다 */
        val lines: List<Line>,
    )

    data class Line(val kind: String, val amount: Int)

    private data class Head(
        val settlementId: Long,
        val performanceId: Long,
        val status: String,
        val amount: Int,
        val settleAt: OffsetDateTime,
        val settledAt: OffsetDateTime?,
    )
}
