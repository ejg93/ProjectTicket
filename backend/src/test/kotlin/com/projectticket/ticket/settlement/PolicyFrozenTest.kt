package com.projectticket.ticket.settlement

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 정책 행과 박제 열을 DB 가 굳히는가(`V32`, 점검 15차 `I15-1`). 강제 지점이 주석뿐이던 자리라 SQL 을 직접 던진다 —
 * 앱에 UPDATE 가 없는 것만으로는 `psql` 한 줄을 못 막는다(`D14` 「테스트」).
 *
 * 실패가 트랜잭션을 깨므로 경우마다 시험 하나다(롤백 바탕). `settlement.settle_at` 은 안 굳힌다 — `SettlementAggregateTest` 가
 * 시계를 당긴다(`D8`), 그래서 여기 없다.
 */
class PolicyFrozenTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var openService: PerformanceOpenService

    @Test
    fun a_settlement_policy_row_cannot_be_updated() {
        val id = newSettlementPolicy()

        assertThatThrownBy { jdbc.sql("update settlement_policy set commission_rate = 0.50 where settlement_policy_id = :id").param("id", id).update() }
            .hasStackTraceContaining(FROZEN_POLICY)
    }

    @Test
    fun a_settlement_policy_row_cannot_be_deleted() {
        val id = newSettlementPolicy()

        assertThatThrownBy { jdbc.sql("delete from settlement_policy where settlement_policy_id = :id").param("id", id).update() }
            .hasStackTraceContaining(FROZEN_POLICY)
    }

    @Test
    fun a_refund_fee_tier_row_cannot_be_updated() {
        val id = newRefundFeeTier()

        assertThatThrownBy { jdbc.sql("update refund_fee_tier set rate = 0.90 where refund_fee_tier_id = :id").param("id", id).update() }
            .hasStackTraceContaining(FROZEN_POLICY)
    }

    @Test
    fun a_refund_fee_tier_row_cannot_be_deleted() {
        val id = newRefundFeeTier()

        assertThatThrownBy { jdbc.sql("delete from refund_fee_tier where refund_fee_tier_id = :id").param("id", id).update() }
            .hasStackTraceContaining(FROZEN_POLICY)
    }

    @Test
    fun an_open_performance_cannot_be_restamped() {
        val performanceId = openPerformance("frozen-restamp")
        val other = newSettlementPolicy()

        assertThatThrownBy {
            jdbc.sql("update performance set settlement_policy_id = :policy where performance_id = :id")
                .param("policy", other).param("id", performanceId).update()
        }.hasStackTraceContaining("회차의 정산 정책 박제는 고칠 수 없다")
    }

    @Test
    fun a_settlement_cannot_switch_its_policy() {
        val performanceId = openPerformance("frozen-settle")
        val stamp = jdbc.sql("select settlement_policy_id from performance where performance_id = :id").param("id", performanceId)
            .query(Long::class.java).single()
        val settlementId = jdbc.sql("insert into settlement (performance_id, settlement_policy_id, settle_at) values (:p, :policy, now()) returning settlement_id")
            .param("p", performanceId).param("policy", stamp).query(Long::class.java).single()
        val other = newSettlementPolicy()

        // `V23` 은 넣을 때만 회차와 대조한다 — 넣은 뒤 바꾸면 `takeDue` 가 남의 요율로 센다.
        assertThatThrownBy {
            jdbc.sql("update settlement set settlement_policy_id = :policy where settlement_id = :id")
                .param("policy", other).param("id", settlementId).update()
        }.hasStackTraceContaining("정산서의 정책은 고칠 수 없다")
    }

    @Test
    fun a_refund_cannot_change_its_stamped_rate() {
        val refundId = newRefund("frozen-refund")

        assertThatThrownBy { jdbc.sql("update refund set tier_rate = 0.20 where refund_id = :id").param("id", refundId).update() }
            .hasStackTraceContaining("환불의 율·일수·금액 박제는 고칠 수 없다")
    }

    @Test
    fun a_performance_seat_cannot_change_its_price() {
        val performanceId = openPerformance("frozen-price")
        val seatId = EventFixture(jdbc).performanceSeatIds(performanceId).first()

        assertThatThrownBy { jdbc.sql("update performance_seat set price = 1 where performance_seat_id = :id").param("id", seatId).update() }
            .hasStackTraceContaining("회차 좌석의 가격 박제는 고칠 수 없다")
    }

    @Test
    fun a_new_policy_row_is_still_inserted() {
        newSettlementPolicy()
        newRefundFeeTier()
    }

    @Test
    fun a_draft_is_stamped_from_null() {
        val fixture = EventFixture(jdbc)
        val performanceId = fixture.performance(eventId = fixture.event(fixture.organizer("frozen-draft")), hallId = fixture.hall())

        assertThat(fixture.stampDefaultPolicy(performanceId)).isPositive()
    }

    private fun newSettlementPolicy(): Long =
        jdbc.sql(
            """
            insert into settlement_policy (organizer_id, effective_at, commission_rate, cancel_fee_share, payout_delay_days)
            values (null, '2099-01-01T00:00:00Z', 0.10, 1.00, 7)
            returning settlement_policy_id
            """,
        ).query(Long::class.java).single()

    private fun newRefundFeeTier(): Long =
        jdbc.sql("insert into refund_fee_tier (days_before_min, rate, effective_at) values (0, 0.10, '2099-01-01T00:00:00Z') returning refund_fee_tier_id")
            .query(Long::class.java).single()

    /** 좌석 하나·등급 하나로 연 회차. `open` 이 정책을 박제하고 좌석 가격을 베낀다 */
    private fun openPerformance(organizerCode: String): Long {
        val fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer(organizerCode))
        val hallId = fixture.hall()
        fixture.seats(hallId, "F1-A", 1)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        return fixture.performance(eventId, hallId, startsInDays = 8).also { openService.open(it, actorAccountId = null) }
    }

    /** 결제까지 마친 예매의 환불. 롤백 바탕이라 환불 등식(지연 트리거)은 안 돈다 */
    private fun newRefund(organizerCode: String): Long {
        val fixture = EventFixture(jdbc)
        val performanceId = openPerformance(organizerCode)
        val reservationId = fixture.hold(fixture.account("$organizerCode@test.local"), performanceId, "F1-A", 1)
        fixture.reserve(reservationId)
        return jdbc.sql(
            """
            insert into refund (payment_id, reason, days_before, tier_rate, fee_amount, refund_amount)
            select payment_id, 'audience', 8, 0.10, 15400, 138600 from payment where reservation_id = :id
            returning refund_id
            """,
        ).param("id", reservationId).query(Long::class.java).single()
    }

    private companion object {
        const val FROZEN_POLICY = "정책 행은 고치거나 지우지 않는다"
    }
}
