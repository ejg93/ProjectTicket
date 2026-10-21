package com.projectticket.ticket.settlement

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.event.EventFixture
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 정산의 유일 제약 둘을 직접 친다(`V16`, 점검 7차 `I7`). 둘 다 도메인 불변식이라 `D8` 「무엇을 치고 무엇을 안 치나」의 대상이다.
 *
 * - `settlement_policy_key` 는 `unique nulls not distinct` 다 — 기본 정책(`organizer_id` null)이 같은 시행 시각에 둘이면
 *   어느 것이 이기는지가 안 정해진다. 보통의 유일 제약은 null 을 서로 다른 값으로 보므로 **이 절이 실제로 도는지**는 쳐 봐야 안다
 *   (Postgres 15 부터다 — 이미지가 낮으면 마이그레이션 자체가 죽지만, 그것은 기동이 잡는다).
 * - `settlement_line_key` 는 같은 종류의 항목이 한 정산서에 둘 들어오는 것을 막는다 — 들어오면 합이 두 번 센다.
 * - `settlement_policy_matches_performance_check`(`V23`)는 정산서의 정책이 회차의 박제와 다르면 막는다 — 다르면 남의 요율로 수수료를 센다.
 */
class SettlementKeyTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun two_default_policies_at_the_same_effective_time_are_rejected() {
        val insert = """
            insert into settlement_policy (organizer_id, effective_at, commission_rate, cancel_fee_share, payout_delay_days)
            values (null, '2099-01-01T00:00:00Z', 0.10, 1.00, 7)
        """
        jdbc.sql(insert).update()

        assertThatThrownBy { jdbc.sql(insert).update() }
            .describedAs("nulls not distinct 가 아니면 null 둘은 서로 다른 값이라 둘째가 들어간다")
            .hasStackTraceContaining("settlement_policy_key")
    }

    @Test
    fun the_same_line_kind_cannot_appear_twice_in_one_settlement() {
        val fixture = EventFixture(jdbc)
        val performanceId = fixture.performance(eventId = fixture.event(fixture.organizer("settle-key")), hallId = fixture.hall())
        val policyId = fixture.stampDefaultPolicy(performanceId)
        val settlementId = jdbc.sql(
            """
            insert into settlement (performance_id, settlement_policy_id, settle_at, status, amount, settled_at)
            values (:performance, :policy,
                    now(), 'pending', 100000, now())
            returning settlement_id
            """,
        ).param("performance", performanceId).param("policy", policyId).query(Long::class.java).single()
        val line = "insert into settlement_line (settlement_id, kind, amount) values (:id, 'sale', 100000)"
        jdbc.sql(line).param("id", settlementId).update()

        assertThatThrownBy { jdbc.sql(line).param("id", settlementId).update() }
            .hasStackTraceContaining("settlement_line_key")
    }

    @Test
    fun settlement_on_an_unstamped_performance_is_rejected() {
        val performanceId = draftPerformance("settle-draft")
        val current = jdbc.sql("select settlement_policy_id from settlement_policy where organizer_id is null and effective_at <= now() order by effective_at desc limit 1")
            .query(Long::class.java).single()

        // 실패가 트랜잭션을 깨므로 경우마다 시험 하나다(롤백 바탕).
        assertThatThrownBy { insertSettlement(performanceId, current) }
            .describedAs("박제 전(draft) 회차는 비교할 정책이 없다")
            .hasStackTraceContaining("정산서의 정책이 회차의 박제와 다르다")
    }

    @Test
    fun settlement_with_a_policy_other_than_the_stamp_is_rejected() {
        val performanceId = draftPerformance("settle-stamp")
        EventFixture(jdbc).stampDefaultPolicy(performanceId)
        val other = jdbc.sql(
            """
            insert into settlement_policy (organizer_id, effective_at, commission_rate, cancel_fee_share, payout_delay_days)
            values (null, '2099-01-01T00:00:00Z', 0.20, 1.00, 7)
            returning settlement_policy_id
            """,
        ).query(Long::class.java).single()

        assertThatThrownBy { insertSettlement(performanceId, other) }
            .describedAs("다른 정책이면 takeDue 가 그 요율로 수수료를 센다")
            .hasStackTraceContaining("정산서의 정책이 회차의 박제와 다르다")
    }

    private fun draftPerformance(organizerCode: String): Long {
        val fixture = EventFixture(jdbc)
        return fixture.performance(eventId = fixture.event(fixture.organizer(organizerCode)), hallId = fixture.hall())
    }

    private fun insertSettlement(performanceId: Long, policyId: Long) =
        jdbc.sql("insert into settlement (performance_id, settlement_policy_id, settle_at) values (:performance, :policy, now())")
            .param("performance", performanceId).param("policy", policyId).update()
}
