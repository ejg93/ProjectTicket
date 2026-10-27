package com.projectticket.ticket.idempotency

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.event.EventFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * 멱등키의 2초 대기 한도가 **키를 잡는 insert 에만** 걸리나(`D4` 「멱등키」, `I11-1`).
 *
 * `set local` 은 트랜잭션 끝까지 남는다. 되돌리지 않으면 [IdempotencyService.run] 안에서 도는 좌석 `for update` 까지
 * 2초에 끊기고, 그 55P03 은 `claim` 의 409 도 교착 재시도도 아니라 500 으로 나갔다. 작업 안에서 설정값을 읽어 잰다.
 */
class IdempotencyLockTimeoutTest : PostgresTestBase() {

    @Autowired lateinit var idempotency: IdempotencyService
    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun the_work_inside_runs_without_the_claim_timeout() {
        val accountId = EventFixture(jdbc).account("lock-timeout@test.local")
        val before = currentLockTimeout()

        val inside = idempotency.run(accountId = accountId, key = UUID.randomUUID().toString(), request = mapOf("x" to 1), responseType = String::class.java) {
            currentLockTimeout()
        }

        assertThat(inside).describedAs("작업 안의 lock_timeout 이 claim 의 2초가 아니라 원래 값이어야 한다").isEqualTo(before)
        assertThat(inside).isNotEqualTo("2s")
    }

    private fun currentLockTimeout(): String = jdbc.sql("show lock_timeout").query(String::class.java).single()
}
