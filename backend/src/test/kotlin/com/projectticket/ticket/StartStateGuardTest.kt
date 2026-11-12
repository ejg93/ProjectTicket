package com.projectticket.ticket

import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * 환불·정산서·알림이 전이표의 생성 상태로만 태어나는가(`V33`, `state-machines.md` 생성 행, 점검 15차 `I15-2`).
 * 전이 트리거는 update 만 보므로 끝 상태로 바로 넣으면 전이를 통째로 건너뛴다 — 칸 짝 check 는 열 조합만 본다.
 *
 * 넣는 행은 칸 짝 check 를 다 맞춘다 — 가드가 없으면 들어가야 이 시험이 가드를 잰다. 실패가 트랜잭션을 깨므로 경우마다 시험 하나다.
 */
class StartStateGuardTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var openService: PerformanceOpenService

    @Test
    fun a_refund_cannot_be_born_done() {
        val fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("start-refund"))
        val hallId = fixture.hall()
        fixture.seats(hallId, "F1-A", 1)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        val performanceId = fixture.performance(eventId, hallId, startsInDays = 8).also { openService.open(it, actorAccountId = null) }
        val reservationId = fixture.hold(fixture.account("start-refund@test.local"), performanceId, "F1-A", 1)
        fixture.reserve(reservationId)

        assertThatThrownBy {
            jdbc.sql(
                """
                insert into refund (payment_id, reason, days_before, tier_rate, fee_amount, refund_amount, status, refund_number, done_at)
                select payment_id, 'audience', 8, 0.10, 15400, 138600, 'done', 'R-START', now() from payment where reservation_id = :id
                """,
            ).param("id", reservationId).update()
        }.hasStackTraceContaining("환불은 requested 로만 만들 수 있다")
    }

    @Test
    fun a_settlement_cannot_be_born_paid() {
        val fixture = EventFixture(jdbc)
        val performanceId = fixture.performance(eventId = fixture.event(fixture.organizer("start-settle")), hallId = fixture.hall())
        val policyId = fixture.stampDefaultPolicy(performanceId)

        assertThatThrownBy {
            jdbc.sql(
                """
                insert into settlement (performance_id, settlement_policy_id, settle_at, status, amount, settled_at)
                values (:performance, :policy, now(), 'paid', 0, now())
                """,
            ).param("performance", performanceId).param("policy", policyId).update()
        }.hasStackTraceContaining("정산서는 scheduled 로만 만들 수 있다")
    }

    @Test
    fun a_notification_cannot_be_born_sent() {
        val accountId = EventFixture(jdbc).account("start-notify@test.local")

        assertThatThrownBy {
            jdbc.sql(
                """
                insert into notification (event_id, event_type, account_id, subject, body, status, sent_at)
                values (:event, 'reservation.reserved', :account, '제목', '본문', 'sent', now())
                """,
            ).param("event", UUID.randomUUID()).param("account", accountId).update()
        }.hasStackTraceContaining("알림은 pending 으로만 만들 수 있다")
    }
}
