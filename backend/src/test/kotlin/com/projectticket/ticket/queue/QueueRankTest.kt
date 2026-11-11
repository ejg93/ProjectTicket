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
    private var eventId: Long = 0
    private var hallId: Long = 0
    private var first: Long = 0
    private var second: Long = 0
    private var third: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)

        // **앞에 한 명을 세워 둔다.** 줄이 비어 있으면 진입이 곧 입장이라(`D12` 「항상 켠다」) 순번이라는 것이 안 생긴다 —
        // 순번을 재는 이 테스트들은 「이미 줄이 있는」 상황을 본다.
        redis.opsForZSet().add(QueueKeys.waiting(performanceId), "head", 0.0)

        first = fixture.account("${PREFIX}1@test.local")
        second = fixture.account("${PREFIX}2@test.local")
        third = fixture.account("${PREFIX}3@test.local")
    }

    @Test
    fun ranks_follow_entry_order() {
        assertThat(queue.enter(performanceId, first).rank).isEqualTo(2L)
        assertThat(queue.enter(performanceId, second).rank).isEqualTo(3L)
        assertThat(queue.enter(performanceId, third).rank).isEqualTo(4L)
    }

    @Test
    fun re_entering_keeps_the_first_place() {
        queue.enter(performanceId, first)
        queue.enter(performanceId, second)

        // 새로고침이 이 모양이다. 진입 시각을 다시 쓰면 뒤로 밀린다.
        assertThat(queue.enter(performanceId, first).rank).isEqualTo(2L)
        assertThat(queue.position(performanceId, second).rank).isEqualTo(3L)
        // 한 사람이 줄에 두 번 서지도 않는다 — member 가 계정이라 그렇다(앞에 세워 둔 하나까지 셋).
        assertThat(redis.opsForZSet().size(QueueKeys.waiting(performanceId))).isEqualTo(3)
    }

    @Test
    fun eta_comes_from_the_admission_rate() {
        assertThat(queue.enter(performanceId, first).etaSeconds).isEqualTo(1L)

        // 앞을 한 초치(20명)로 채운다. 21번째는 둘째 초에 들어간다 — 올림이라 1이 아니라 2다.
        repeat(QueueService.ADMIT_PER_SECOND.toInt() - 2) { index ->
            redis.opsForZSet().add(QueueKeys.waiting(performanceId), "filler-$index", index.toDouble())
        }
        val entered = queue.enter(performanceId, second)
        assertThat(entered.rank).isEqualTo(21L)
        assertThat(entered.etaSeconds).isEqualTo(2L)
    }

    @Test
    fun asking_before_entering_is_not_in_queue() {
        assertThatThrownBy { queue.position(performanceId, first) }
            .isInstanceOfSatisfying(TicketException::class.java) { assertThat(it.code).isEqualTo(ErrorCode.NOT_IN_QUEUE) }
    }

    @Test
    fun before_sales_open_there_is_no_queue() {
        jdbc.sql("update performance set sales_open_at = now() + interval '1 hour' where performance_id = :id").param("id", performanceId).update()

        // 판매 시작 전에는 줄이 없다(`I13-1`). 관문도 안 지킨다 — 선점 서비스가 409 로 답한다.
        assertThatThrownBy { queue.enter(performanceId, first) }
            .isInstanceOfSatisfying(TicketException::class.java) { assertThat(it.code).isEqualTo(ErrorCode.QUEUE_CLOSED) }
        assertThat(queue.gateOf(performanceId)).isEqualTo(QueueService.Gate.BEFORE_SALE)
    }

    @Test
    fun a_closed_performance_has_no_queue() {
        // 마감이 지나야 닫힌다(`V30`). 트리거가 갱신 전 마감을 봐서 두 문장이다 — 한 문장에 마감을 같이 당기면 우회가 된다.
        jdbc.sql("update performance set sales_close_at = now() - interval '1 second' where performance_id = :id").param("id", performanceId).update()
        jdbc.sql("update performance set status = 'closed' where performance_id = :id").param("id", performanceId).update()

        // 「있었는데 끝났다」라 410 이다. 없는 회차는 404 고, 화면이 그 둘을 다르게 말한다.
        assertThatThrownBy { queue.enter(performanceId, first) }
            .isInstanceOfSatisfying(TicketException::class.java) { assertThat(it.code).isEqualTo(ErrorCode.QUEUE_CLOSED) }
        assertThatThrownBy { queue.enter(performanceId = -1, accountId = first) }
            .isInstanceOfSatisfying(TicketException::class.java) { assertThat(it.code).isEqualTo(ErrorCode.PERFORMANCE_NOT_FOUND) }
    }

    @Test
    fun closing_the_performance_drops_the_queue() {
        queue.enter(performanceId, first)
        jdbc.sql("update performance set sales_close_at = now() - interval '1 hour' where performance_id = :id")
            .param("id", performanceId)
            .update()

        assertThat(closer.closeDue()).isEqualTo(1)

        // 키를 안 걷으면 다음 오픈까지 남아서, 새 회차의 줄이 옛날 사람 뒤에 선다.
        assertThat(redis.hasKey(QueueKeys.waiting(performanceId))).isFalse()
    }

    @Test
    fun a_closed_performance_is_gone_at_once() {
        queue.enter(performanceId, first)
        // 폴링이 판매 상태를 한 번 읽어 담는다(`55`) — 닫은 대가 그 항목을 안 지우면 다음 폴링이 TTL 동안 「판매 중」을 믿는다
        queue.position(performanceId, first)
        jdbc.sql("update performance set sales_close_at = now() - interval '1 hour' where performance_id = :id")
            .param("id", performanceId)
            .update()
        assertThat(closer.closeDue()).isEqualTo(1)

        assertThatThrownBy { queue.position(performanceId, first) }
            .isInstanceOfSatisfying(TicketException::class.java) { assertThat(it.code).isEqualTo(ErrorCode.QUEUE_CLOSED) }
    }

    @Test
    fun another_instance_closing_answers_410_not_404() {
        queue.enter(performanceId, first)
        queue.position(performanceId, first)
        closeElsewhere()

        // 줄이 걷힌 것을 보면 DB 를 다시 읽는다 — 404 `not-in-queue` 면 화면이 「다시 줄을 서 주세요」로 멈춘다
        assertThatThrownBy { queue.position(performanceId, first) }
            .isInstanceOfSatisfying(TicketException::class.java) { assertThat(it.code).isEqualTo(ErrorCode.QUEUE_CLOSED) }
    }

    @Test
    fun entering_after_another_instance_closed_makes_no_queue() {
        queue.enter(performanceId, first)
        queue.position(performanceId, first)
        closeElsewhere()

        assertThatThrownBy { queue.enter(performanceId, second) }
            .isInstanceOfSatisfying(TicketException::class.java) { assertThat(it.code).isEqualTo(ErrorCode.QUEUE_CLOSED) }
        // 닫힌 회차의 줄은 입장·청소 스케줄러가 안 훑어서(`status = open` 만) 한 번 서면 안 지워진다
        assertThat(redis.hasKey(QueueKeys.waiting(performanceId))).isFalse()
    }

    @Test
    fun opening_a_draft_opens_the_gate_at_once() {
        val draft = fixture.performance(eventId, hallId, startsInDays = 2)
        assertThat(queue.gateOf(draft)).isEqualTo(QueueService.Gate.NONE)

        openService.open(draft, actorAccountId = null)

        // `draft` 를 담았으면 TTL 동안 관문이 안 지켜 토큰 없이 선점이 지난다
        assertThat(queue.gateOf(draft)).isEqualTo(QueueService.Gate.GATED)
    }

    @Test
    fun the_endpoint_answers_rank_and_eta() {
        val principal = TicketUser(id = first, email = "${PREFIX}1@test.local", role = AccountRole.AUDIENCE, passwordHash = null, active = true)

        mvc.post("/api/queue/$performanceId") { with(user(principal)); with(csrf()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.state") { value("waiting") }
                jsonPath("$.rank") { value(2) }
                jsonPath("$.eta_seconds") { value(1) }
            }
    }

    /** 다른 대가 회차를 닫은 꼴 — DB 와 Redis 줄은 같이 바뀌고 이 대의 판매 상태 캐시는 안 지워진다 */
    private fun closeElsewhere() {
        // 마감이 지나야 닫힌다(`V30`) — 두 문장이다
        jdbc.sql("update performance set sales_close_at = now() - interval '1 second' where performance_id = :id").param("id", performanceId).update()
        jdbc.sql("update performance set status = 'closed' where performance_id = :id").param("id", performanceId).update()
        redis.delete(QueueKeys.ofPerformance(performanceId))
    }

    private companion object {
        /** 같은 컨테이너를 쓰는 다른 테스트와 이름이 안 겹치게 */
        const val PREFIX = "queue-rank-"
    }
}
