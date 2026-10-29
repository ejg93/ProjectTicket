package com.projectticket.ticket.settlement

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.event.EventFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 정산 전이 트리거(`V25`, 점검 11차 `I11-6`)가 `D3` 전이표대로 막는가.
 *
 * 금액 합계는 지연 트리거라 롤백 바탕에서는 안 돈다 — 여기서는 전이만 잰다. 실패가 트랜잭션을 깨므로 거절은 시험마다 하나다.
 */
class SettlementTransitionTriggerTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient

    private var settlementId: Long = 0

    @BeforeEach
    fun setUp() {
        val fixture = EventFixture(jdbc)
        val performanceId = fixture.performance(eventId = fixture.event(fixture.organizer("settle-transition")), hallId = fixture.hall())
        settlementId = jdbc.sql(
            """
            insert into settlement (performance_id, settlement_policy_id, settle_at, status, amount, settled_at)
            values (:performance, :policy, now(), 'pending', 0, now())
            returning settlement_id
            """,
        ).param("performance", performanceId)
            .param("policy", fixture.stampDefaultPolicy(performanceId))
            .query(Long::class.java)
            .single()
    }

    @Test
    fun pending_goes_forward_to_paid() {
        setStatus("confirmed")
        setStatus("paid")

        assertThat(jdbc.sql("select status from settlement where settlement_id = :id").param("id", settlementId).query(String::class.java).single())
            .isEqualTo("paid")
    }

    @Test
    fun pending_cannot_go_back_to_scheduled() {
        // 되돌린 정산서는 스케줄러가 한 번 더 집계한다 — 집계의 조건부 UPDATE 가 `scheduled` 만 본다.
        assertThatThrownBy { setStatus("scheduled") }
            .hasStackTraceContaining("할 수 없는 정산 상태 전이다")
    }

    @Test
    fun pending_cannot_skip_to_paid() {
        assertThatThrownBy { setStatus("paid") }
            .hasStackTraceContaining("할 수 없는 정산 상태 전이다")
    }

    private fun setStatus(status: String) {
        jdbc.sql("update settlement set status = :status where settlement_id = :id")
            .param("status", status)
            .param("id", settlementId)
            .update()
    }
}
