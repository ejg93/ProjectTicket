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
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.http.MediaType
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.mock.web.MockHttpServletResponse
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
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var redis: LettuceConnectionFactory

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
        val hold = readyToHold()

        // 회차 취소·종료가 회차 행을 쥔 모양이다. 상한이 없으면 선점이 연결을 쥐고 4초를 기다린다.
        val locked = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        val holding = holder.submit {
            TransactionTemplate(txManager).executeWithoutResult {
                jdbc.sql("select 1 from performance where performance_id = :id for update").param("id", hold.performanceId).query(Int::class.java).single()
                locked.countDown()
                Thread.sleep(HOLD_MS)
            }
        }
        try {
            check(locked.await(10, TimeUnit.SECONDS)) { "회차 행을 못 잡았다" }

            assertServiceBusy(hold.send())
        } finally {
            holding.get(30, TimeUnit.SECONDS)
            holder.shutdown()
        }
    }

    @Test
    fun a_request_behind_an_empty_pool_gets_503_after_three_seconds() {
        val hold = readyToHold()
        val pool = dataSource.unwrap(HikariDataSource::class.java)

        // 풀의 연결을 전부 빌려 쥔다 — 손으로 만든 예외가 아니라 Hikari 가 실제로 3초 기다리고 포기하는 것을 본다(`I17-13`).
        val borrowed = mutableListOf<Connection>()
        val response = try {
            // 빌리는 것도 `try` 안이다 — 중간에 던지면 앞서 빌린 것이 새어 뒤 시험이 줄어든 풀로 돈다(마무리 37차 m2).
            repeat(pool.maximumPoolSize) { borrowed += pool.connection }
            val started = System.nanoTime()
            hold.send().also {
                val tookMs = (System.nanoTime() - started) / 1_000_000
                assertThat(tookMs).describedAs("Hikari connection-timeout 3초").isBetween(POOL_WAIT_FLOOR_MS, POOL_WAIT_CEILING_MS)
            }
        } finally {
            borrowed.forEach { it.close() }
        }

        assertServiceBusy(response)
    }

    @Test
    fun the_real_beans_carry_the_limits() {
        // 이름만 묶으면 커스텀 빈이 설정을 무시해도 초록이다 — 전체 컨텍스트가 실제로 주입한 빈을 본다(`I17-13`).
        assertThat(dataSource.unwrap(HikariDataSource::class.java).connectionTimeout).isEqualTo(3_000)
        assertThat(redis.clientConfiguration.commandTimeout).isEqualTo(Duration.ofSeconds(2))
        val socket = redis.clientConfiguration.clientOptions.map { it.socketOptions }.orElseThrow()
        assertThat(socket.connectTimeout).isEqualTo(Duration.ofSeconds(1))
    }

    /** 선점 직전까지 — 열린 회차, 대기열을 지난 구매자, 좌석 하나 */
    private fun readyToHold(): Hold {
        val fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("${PREFIX}t${System.nanoTime() % 1_000_000_000}"))
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
        return Hold(performanceId = performanceId, seatId = seatId, buyer = buyer, token = token)
    }

    private inner class Hold(val performanceId: Long, val seatId: Long, val buyer: TicketUser, val token: String) {
        fun send(): MockHttpServletResponse =
            mvc.post("/api/performances/$performanceId/reservations") {
                with(user(buyer))
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = json.writeValueAsString(mapOf("seat_ids" to listOf(seatId)))
                header("Idempotency-Key", UUID.randomUUID().toString())
                header(QueueGateFilter.ADMISSION_HEADER, token)
            }.andReturn().response
    }

    private fun assertServiceBusy(response: MockHttpServletResponse) {
        assertThat(response.status).isEqualTo(503)
        assertThat(response.getHeader("Retry-After")).isNotBlank()
        assertThat(response.contentAsString).contains("service-busy")
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

        /** 풀 대기 3초의 앞뒤 — 응답 조립 몫을 뒤에 더 준다. `connection-timeout` 이 5초면 뒤를 넘는다 */
        const val POOL_WAIT_FLOOR_MS = 2_500L
        const val POOL_WAIT_CEILING_MS = 4_500L
    }
}
