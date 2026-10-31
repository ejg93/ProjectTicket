package com.projectticket.ticket.notification

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.event.EventFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * 알림 상태는 `pending` 에서 끝 상태 셋으로만 간다(`V29`, `state-machines.md` 「알림」). SQL 을 직접 던진다 — `NotificationStore` 의
 * 조건부 UPDATE 가 먼저 막아서 트리거가 도는지를 못 본다(`SeatTransitionTriggerTest` 와 같은 이유).
 *
 * 막히는 경우는 **시험마다 하나씩 맨 끝에** 둔다 — 예외가 난 트랜잭션은 다음 문장을 못 돈다(`25P02`).
 */
class NotificationTransitionTriggerTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient

    private var accountId: Long = 0

    @BeforeEach
    fun setUp() {
        accountId = EventFixture(jdbc).account("notify-trigger@test.local")
    }

    @Test
    fun pending_goes_to_each_end_state() {
        assertThat(update(pending(), "status = 'sent', sent_at = now()")).describedAs("pending→sent").isEqualTo(1)
        assertThat(update(pending(), "status = 'failed', failure_reason = 'smtp'")).describedAs("pending→failed").isEqualTo(1)
        assertThat(update(pending(), "status = 'skipped', failure_reason = 'withdrawn'")).describedAs("pending→skipped").isEqualTo(1)
    }

    @Test
    fun a_sent_notification_cannot_go_back_to_pending() {
        val id = pending()
        update(id, "status = 'sent', sent_at = now()")

        assertRejected { update(id, "status = 'pending', sent_at = null") }
    }

    @Test
    fun a_failed_notification_cannot_become_sent() {
        val id = pending()
        update(id, "status = 'failed', failure_reason = 'smtp'")

        assertRejected { update(id, "status = 'sent', sent_at = now(), failure_reason = null") }
    }

    private fun pending(): Long =
        jdbc.sql(
            """
            insert into notification (event_id, event_type, account_id, subject, body)
            values (:event, 'reservation.reserved', :account, '제목', '본문')
            returning notification_id
            """,
        )
            .param("event", UUID.randomUUID())
            .param("account", accountId)
            .query(Long::class.java)
            .single()

    private fun update(id: Long, set: String): Int =
        jdbc.sql("update notification set $set where notification_id = :id").param("id", id).update()

    private fun assertRejected(statement: () -> Unit) {
        val thrown = catchThrowable(statement)
        assertThat(thrown).withFailMessage("트리거가 안 막았다 — 끝난 알림이 다른 상태로 갔다").isNotNull()
        assertThat(thrown).hasStackTraceContaining("할 수 없는 알림 상태 전이다")
    }
}
