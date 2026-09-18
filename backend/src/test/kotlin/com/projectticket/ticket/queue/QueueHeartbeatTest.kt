package com.projectticket.ticket.queue

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete

/**
 * 이탈과 새로고침(`D12` 「이탈과 정리」, 청크 24의 닫힘 조건).
 *
 * **강제 지점이 시각 둘이다.** 하트비트는 폴링이 남기고 90초가 지나면 스윕이 줄에서 뺀다 —
 * 안 빼면 브라우저를 닫은 사람이 앞자리를 계속 먹어서 뒷사람이 그만큼 늦게 들어간다.
 *
 * 새로고침이 순번을 잃지 않는 것(`ZADD NX`)은 [QueueRankTest] 가 잰다. 여기는 그 반대쪽 — **떠난 사람을 어떻게 아나**다.
 */
class QueueHeartbeatTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var queue: QueueService
    @Autowired lateinit var admission: AdmissionService
    @Autowired lateinit var sweeper: QueueSweeper
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var redis: StringRedisTemplate
    @Autowired lateinit var jdbc: JdbcClient

    private lateinit var fixture: EventFixture
    private var performanceId: Long = 0
    private var walker: Long = 0
    private var stayer: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        val hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)

        // 앞을 막아 둔다. 줄이 비어 있으면 진입이 곧 입장이라 기다리는 사람이 안 생긴다.
        redis.opsForZSet().add(QueueKeys.waiting(performanceId), "head", 0.0)
        walker = fixture.account("${PREFIX}walker@test.local")
        stayer = fixture.account("${PREFIX}stayer@test.local")
    }

    @Test
    fun entering_leaves_the_first_heartbeat() {
        queue.enter(performanceId, walker)

        // 진입이 자국을 안 남기면, 한 번도 폴링 안 한 사람은 영원히 안 걷힌다.
        assertThat(redis.opsForHash<String, String>().get(QueueKeys.seen(performanceId), walker.toString()))
            .isNotNull()
    }

    @Test
    fun a_silent_waiter_is_swept_and_a_polling_one_stays() {
        queue.enter(performanceId, walker)
        queue.enter(performanceId, stayer)
        goSilent(walker)
        goSilent(stayer)

        // 폴링이 하트비트를 겸한다(`D12`). 이 호출 하나가 「아직 보고 있다」는 말이다.
        queue.position(performanceId, stayer)

        assertThat(queue.sweepStale(performanceId)).isEqualTo(1)
        assertThat(rankOf(walker)).isNull()
        assertThat(rankOf(stayer)).isNotNull()
        // 줄과 하트비트를 같이 지운다. 하나만 지우면 다음 진입이 유령을 깨우거나 줄에 영원히 남는다.
        assertThat(redis.opsForHash<String, String>().hasKey(QueueKeys.seen(performanceId), walker.toString()))
            .isFalse()
    }

    @Test
    fun the_sweeper_walks_the_open_performances() {
        queue.enter(performanceId, walker)
        goSilent(walker)

        assertThat(sweeper.sweepDue()).isEqualTo(1)
        assertThat(rankOf(walker)).isNull()
    }

    @Test
    fun leaving_gives_the_admission_back() {
        // 앞을 비워 바로 들어가게 한다.
        redis.delete(QueueKeys.waiting(performanceId))
        queue.enter(performanceId, walker)
        assertThat(admission.tokenFor(performanceId, walker)).isNotNull()

        mvc.delete("/api/queue/$performanceId") { with(user(principal(walker))); with(csrf()) }
            .andExpect { status { isNoContent() } }

        // 정원을 쥔 채로 사라지면 그 자리는 TTL 10분 동안 아무도 못 쓴다.
        assertThat(admission.tokenFor(performanceId, walker)).isNull()
        assertThat(redis.opsForZSet().size(QueueKeys.active(performanceId))).isZero()
    }

    @Test
    fun leaving_twice_is_still_no_content() {
        queue.enter(performanceId, walker)
        repeat(2) {
            mvc.delete("/api/queue/$performanceId") { with(user(principal(walker))); with(csrf()) }
                .andExpect { status { isNoContent() } }
        }
        assertThat(rankOf(walker)).isNull()
    }

    /** `rank` 는 플랫폼 타입이라 널 검사를 코틀린 쪽에서 받아 둔다 — 안 그러면 없는 사람을 물을 때 NPE 다 */
    private fun rankOf(accountId: Long): Long? =
        redis.opsForZSet().rank(QueueKeys.waiting(performanceId), accountId.toString())

    /** 하트비트를 시간 밖으로 밀어 둔다. 90초를 실제로 기다릴 수는 없다 */
    private fun goSilent(accountId: Long) {
        val longAgo = System.currentTimeMillis() - QueueService.HEARTBEAT_TIMEOUT.toMillis() * 2
        redis.opsForHash<String, String>().put(QueueKeys.seen(performanceId), accountId.toString(), longAgo.toString())
    }

    private fun principal(accountId: Long): TicketUser =
        TicketUser(accountId, "${PREFIX}$accountId@test.local", AccountRole.AUDIENCE, passwordHash = null, active = true)

    private companion object {
        const val PREFIX = "heartbeat-"
    }
}
