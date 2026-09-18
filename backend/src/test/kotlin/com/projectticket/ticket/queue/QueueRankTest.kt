package com.projectticket.ticket.queue

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.reservation.PerformanceCloser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * 줄이 지켜야 하는 것(`D12`, 청크 21의 닫힘 조건).
 *
 * **강제 지점이 여기다.** 순번은 Redis 의 ZSET score 하나로 정해져서 DB 제약도 타입도 못 막는다 —
 * `ZADD NX` 의 `NX` 한 글자가 규약이고, 그 글자가 빠지면 새로고침할 때마다 순번이 뒤로 밀린다.
 */
class QueueRankTest : PostgresTestBase() {

    @Autowired lateinit var queue: QueueService
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var closer: PerformanceCloser
    @Autowired lateinit var redis: StringRedisTemplate
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var mvc: MockMvc

    private lateinit var fixture: EventFixture
    private var performanceId: Long = 0
    private var first: Long = 0
    private var second: Long = 0
    private var third: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        val hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)

        first = fixture.account("${PREFIX}1@test.local")
        second = fixture.account("${PREFIX}2@test.local")
        third = fixture.account("${PREFIX}3@test.local")
    }

    @Test
    fun ranks_follow_entry_order() {
        assertThat(queue.enter(performanceId, first).rank).isEqualTo(1)
        assertThat(queue.enter(performanceId, second).rank).isEqualTo(2)
        assertThat(queue.enter(performanceId, third).rank).isEqualTo(3)
    }

    @Test
    fun re_entering_keeps_the_first_place() {
        queue.enter(performanceId, first)
        queue.enter(performanceId, second)

        // 새로고침이 이 모양이다. 진입 시각을 다시 쓰면 뒤로 밀린다.
        assertThat(queue.enter(performanceId, first).rank).isEqualTo(1)
        assertThat(queue.position(performanceId, second).rank).isEqualTo(2)
        // 한 사람이 줄에 두 번 서지도 않는다 — member 가 계정이라 그렇다.
        assertThat(redis.opsForZSet().size(QueueKeys.waiting(performanceId))).isEqualTo(2)
    }

    @Test
    fun eta_comes_from_the_admission_rate() {
        assertThat(queue.enter(performanceId, first).etaSeconds).isEqualTo(1)

        // 앞에 한 초치(20명)를 채운다. 21번째는 둘째 초에 들어간다 — 올림이라 1이 아니라 2다.
        repeat(QueueService.ADMIT_PER_SECOND.toInt() - 1) { index ->
            redis.opsForZSet().add(QueueKeys.waiting(performanceId), "filler-$index", index.toDouble())
        }
        val entered = queue.enter(performanceId, second)
        assertThat(entered.rank).isEqualTo(21)
        assertThat(entered.etaSeconds).isEqualTo(2)
    }

    @Test
    fun asking_before_entering_is_not_in_queue() {
        assertThatThrownBy { queue.position(performanceId, first) }
            .isInstanceOfSatisfying(TicketException::class.java) { assertThat(it.code).isEqualTo(ErrorCode.NOT_IN_QUEUE) }
    }

    @Test
    fun a_closed_performance_has_no_queue() {
        jdbc.sql("update performance set status = 'closed' where performance_id = :id").param("id", performanceId).update()

        // 「있었는데 끝났다」라 410 이다. 없는 회차는 404 고, 화면이 그 둘을 다르게 말한다.
        assertThatThrownBy { queue.enter(performanceId, first) }
            .isInstanceOfSatisfying(TicketException::class.java) { assertThat(it.code).isEqualTo(ErrorCode.QUEUE_CLOSED) }
        assertThatThrownBy { queue.enter(-1, first) }
            .isInstanceOfSatisfying(TicketException::class.java) { assertThat(it.code).isEqualTo(ErrorCode.PERFORMANCE_NOT_FOUND) }
    }

    @Test
    fun closing_the_performance_drops_the_queue() {
        queue.enter(performanceId, first)
        jdbc.sql("update performance set sales_close_at = now() - interval '1 hour' where performance_id = :id")
            .param("id", performanceId).update()

        assertThat(closer.closeDue()).isEqualTo(1)

        // 키를 안 걷으면 다음 오픈까지 남아서, 새 회차의 줄이 옛날 사람 뒤에 선다.
        assertThat(redis.hasKey(QueueKeys.waiting(performanceId))).isFalse()
    }

    @Test
    fun the_endpoint_answers_rank_and_eta() {
        val principal = TicketUser(first, "${PREFIX}1@test.local", AccountRole.AUDIENCE, passwordHash = null, active = true)

        mvc.post("/api/queue/$performanceId") { with(user(principal)); with(csrf()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.rank") { value(1) }
                jsonPath("$.eta_seconds") { value(1) }
            }
    }

    private companion object {
        /** 같은 컨테이너를 쓰는 다른 테스트와 이름이 안 겹치게 */
        const val PREFIX = "queue-rank-"
    }
}
