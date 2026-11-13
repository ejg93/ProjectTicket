package com.projectticket.ticket

import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.idempotency.IdempotencyService
import com.projectticket.ticket.outbox.ConsumerErrorHandling
import com.projectticket.ticket.queue.AdmissionService
import com.projectticket.ticket.queue.QueueGateFilter
import com.projectticket.ticket.queue.QueueService
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.backoff.ExponentialBackOff
import tools.jackson.databind.ObjectMapper

/**
 * 앱 연결에 DB 시간 상한이 걸려 있고, 넘으면 503 `service-busy` 로 나가나(`I17-6`, `D4` 「서버가 하는 일」).
 *
 * 상한은 드라이버 속성 `options`(시작 옵션)로 건다. `connection-init-sql` 의 `set` 은 세션 값이라 `IdempotencyService` 의
 * `set local lock_timeout = default` 가 무제한으로 되돌린다 — 그 되돌림 뒤에도 3초인지 따로 잰다.
 * 커밋 레인이다: 다른 트랜잭션이 회차 행을 정말로 쥐고 있어야 선점이 기다린다.
 */
class DbTimeoutTest : ConcurrencyTestBase() {

    @Autowired lateinit var txManager: PlatformTransactionManager
    @Autowired lateinit var idempotency: IdempotencyService
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var queue: QueueService
    @Autowired lateinit var admission: AdmissionService
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var json: ObjectMapper

    @Test
    fun every_app_connection_carries_the_three_limits() {
        assertThat(show("lock_timeout")).isEqualTo("3s")
        assertThat(show("statement_timeout")).isEqualTo("10s")
        assertThat(show("idle_in_transaction_session_timeout")).isEqualTo("30s")
    }

    @Test
    fun the_idempotency_reset_falls_back_to_three_seconds_not_forever() {
        val accountId = EventFixture(jdbc).account("${PREFIX}db-timeout-${System.nanoTime()}@test.local")

        val inside = TransactionTemplate(txManager).execute {
            idempotency.run(accountId = accountId, key = UUID.randomUUID().toString(), request = mapOf("probe" to 1), responseType = String::class.java) {
                show("lock_timeout")
            }
        }

        // `= default` 가 세션 `set` 을 지우고 시작 옵션으로 돌아간다. `connection-init-sql` 로 걸었으면 여기가 `0` 이다.
        assertThat(inside).isEqualTo("3s")
    }

    @Test
    fun a_hold_behind_a_locked_performance_gets_503_service_busy() {
        val fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("${PREFIX}db-timeout"))
        val hallId = fixture.hall(venueName = "${PREFIX}db-timeout-${System.nanoTime()}")
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        val performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
        val seatId = fixture.performanceSeatIds(performanceId).first()
        val email = "${PREFIX}db-timeout-${System.nanoTime()}@test.local"
        val buyer = TicketUser(id = fixture.account(email), email = email, role = AccountRole.AUDIENCE, passwordHash = null, active = true)
        queue.enter(performanceId, buyer.id)
        val token = checkNotNull(admission.tokenFor(performanceId, buyer.id)) { "진입했는데 토큰이 없다" }

        // 회차 취소·종료가 회차 행을 쥔 모양이다. 상한이 없으면 선점이 연결을 쥐고 4초를 기다린다.
        val locked = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        val holding = holder.submit {
            TransactionTemplate(txManager).executeWithoutResult {
                jdbc.sql("select 1 from performance where performance_id = :id for update").param("id", performanceId).query(Int::class.java).single()
                locked.countDown()
                Thread.sleep(HOLD_MS)
            }
        }
        try {
            check(locked.await(10, TimeUnit.SECONDS)) { "회차 행을 못 잡았다" }

            val response = mvc.post("/api/performances/$performanceId/reservations") {
                with(user(buyer))
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = json.writeValueAsString(mapOf("seat_ids" to listOf(seatId)))
                header("Idempotency-Key", UUID.randomUUID().toString())
                header(QueueGateFilter.ADMISSION_HEADER, token)
            }.andReturn().response

            assertThat(response.status).isEqualTo(503)
            assertThat(response.getHeader("Retry-After")).isNotBlank()
            assertThat(response.contentAsString).contains("service-busy")
        } finally {
            holding.get(30, TimeUnit.SECONDS)
            holder.shutdown()
        }
    }

    @Test
    fun a_real_statement_timeout_gets_a_bounded_consumer_retry() {
        val thrown = checkNotNull(
            runCatching {
                TransactionTemplate(txManager).executeWithoutResult {
                    jdbc.sql("set local statement_timeout = '100ms'").update()
                    jdbc.sql("select pg_sleep(1)").query().singleValue()
                }
            }.exceptionOrNull(),
        ) { "문장 상한에 안 걸렸다" }

        assertBoundedRetry(thrown)
    }

    @Test
    fun a_real_lock_timeout_gets_a_bounded_consumer_retry() {
        val accountId = EventFixture(jdbc).account("${PREFIX}db-timeout-${System.nanoTime()}@test.local")
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        val holding = holder.submit {
            TransactionTemplate(txManager).executeWithoutResult {
                jdbc.sql("select 1 from account where account_id = :id for update").param("id", accountId).query(Int::class.java).single()
                locked.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        try {
            check(locked.await(10, TimeUnit.SECONDS)) { "계정 행을 못 잡았다" }
            val thrown = checkNotNull(
                runCatching {
                    TransactionTemplate(txManager).executeWithoutResult {
                        jdbc.sql("set local lock_timeout = '100ms'").update()
                        jdbc.sql("select 1 from account where account_id = :id for update").param("id", accountId).query(Int::class.java).single()
                    }
                }.exceptionOrNull(),
            ) { "잠금 대기 상한에 안 걸렸다" }

            assertBoundedRetry(thrown)
        } finally {
            release.countDown()
            holding.get(30, TimeUnit.SECONDS)
            holder.shutdown()
        }
    }

    /** 드라이버·Spring 이 실제로 던진 예외를 소비자가 받았을 때 — 무제한이 아니라 대기 합 5분 뒤 DLT 다(`I17-12`) */
    private fun assertBoundedRetry(thrown: Throwable) {
        val backOff = ConsumerErrorHandling.backOffFor(ListenerExecutionFailedException("리스너가 던졌다", thrown))

        assertThat(backOff).describedAs(thrown.javaClass.name).isInstanceOf(ExponentialBackOff::class.java)
        assertThat((backOff as ExponentialBackOff).maxElapsedTime).isEqualTo(ConsumerErrorHandling.TRANSIENT_MAX_ELAPSED_MS)
    }

    private fun show(name: String): String = jdbc.sql("show $name").query(String::class.java).single()

    private companion object {
        /** `lock_timeout` 3초보다 길다 — 상한이 없으면 선점이 이만큼 기다린 뒤 201 이다 */
        const val HOLD_MS = 4_000L
    }
}
