package com.projectticket.ticket.queue

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

/**
 * 관문(`D12` 「관문」, 청크 23의 닫힘 조건).
 *
 * **강제 지점이 필터인 이유**는 입구가 늘어도 빠뜨릴 자리가 없어서다 — 서비스에 검사를 넣으면 새 입구가 그것을 안 부른다.
 * 여기서 재는 것은 다섯이다: 토큰 없이 못 산다, 남의 토큰으로 못 산다, 성공하면 반납한다, 실패하면 안 뺏긴다,
 * 그리고 **응답을 못 받은 재시도가 관문을 지나 저장된 답을 받는다**(23a).
 */
class QueueGateTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var queue: QueueService
    @Autowired lateinit var admission: AdmissionService
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var redis: StringRedisTemplate
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: ObjectMapper

    private lateinit var fixture: EventFixture
    private lateinit var buyer: TicketUser
    private var performanceId: Long = 0
    private var otherPerformanceId: Long = 0
    private var seatIds: List<Long> = emptyList()

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        val hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 4)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
        otherPerformanceId = fixture.performance(eventId, hallId, startsInDays = 2)
        openService.open(otherPerformanceId, actorAccountId = null)
        seatIds = fixture.performanceSeatIds(performanceId)

        buyer = principal("${PREFIX}buyer@test.local")
    }

    @Test
    fun no_token_is_rejected_with_the_queue_in_the_body() {
        // 줄에 서 있는 사람이 토큰 없이 눌렀다. 429 는 「지금은 안 되지만 나중엔 된다」다(`D12`).
        redis.opsForZSet().add(QueueKeys.waiting(performanceId), "head", 0.0)
        queue.enter(performanceId, buyer.id)

        hold(seatIds.take(1), token = null).andExpect {
            status { isTooManyRequests() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:admission-required") }
            jsonPath("$.rank") { value(2) }
            jsonPath("$.eta_seconds") { value(1) }
        }
    }

    @Test
    fun a_forged_token_is_rejected_like_a_missing_one() {
        // 「그 토큰은 있었다」를 알려 주지 않는다(`D9`). 없는 것과 만료·위조가 같은 답이다.
        hold(seatIds.take(1), token = "made-up-token").andExpect {
            status { isTooManyRequests() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:admission-required") }
        }
    }

    @Test
    fun an_admitted_buyer_passes_and_gives_the_token_back() {
        val token = admit(buyer.id, performanceId)

        hold(seatIds.take(1), token).andExpect { status { isCreated() } }

        // 좌석을 잡았으면 문지기는 볼일이 끝났다. 반납해야 뒷사람이 들어온다(`D12`).
        // **정원은 활성 집합이 센다** — 토큰 키가 남아 있어도 그 자리는 이미 돌아갔다.
        assertThat(admission.tokenFor(performanceId, buyer.id)).isNull()
        assertThat(redis.opsForZSet().size(QueueKeys.active(performanceId))).isZero()

        // 토큰은 바로 안 죽고 짧은 창만 남는다(23a). 10분 TTL 이 그대로면 반납이 안 된 것이다.
        val leftMs = redis.getExpire(QueueKeys.admission(token), TimeUnit.MILLISECONDS)
        assertThat(leftMs).isPositive().isLessThanOrEqualTo(AdmissionService.RETRY_GRACE.toMillis())
    }

    @Test
    fun a_retry_with_the_same_key_replays() {
        val token = admit(buyer.id, performanceId)
        val key = UUID.randomUUID().toString()

        val first = hold(seatIds.take(1), token, key).andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        // 첫 응답을 못 받은 클라이언트가 같은 키로 다시 온다. 관문이 여기서 429 를 내면
        // `D4` 가 약속한 「저장된 본문을 그대로 돌려준다」에 영영 못 닿는다.
        hold(seatIds.take(1), token, key)
            .andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
            .let { assertThat(it).isEqualTo(first) }
    }

    @Test
    fun the_grace_window_does_not_hand_out_a_second_reservation() {
        val token = admit(buyer.id, performanceId)
        hold(seatIds.take(1), token).andExpect { status { isCreated() } }

        // 창 안에서는 관문을 지나지만 선점은 하나뿐이다 — `reservation_live_hold_idx`(V8)가 둘째 선점을 막는다.
        hold(seatIds.drop(1).take(1), token).andExpect { status { isConflict() } }
    }

    @Test
    fun a_failed_hold_keeps_the_token() {
        val taker = fixture.account("${PREFIX}taker@test.local")
        fixture.hold(taker, performanceId, "F1-A", 1)
        val token = admit(buyer.id, performanceId)

        hold(seatIds.take(1), token).andExpect { status { isConflict() } }

        // 남이 먼저 잡은 좌석을 골랐을 뿐이다. 다른 좌석을 고를 수 있어야 한다(`D12`).
        assertThat(admission.find(token)).isNotNull()
    }

    @Test
    fun someone_elses_token_is_forbidden() {
        val other = principal("${PREFIX}other@test.local")
        val token = admit(other.id, performanceId)

        hold(seatIds.take(1), token).andExpect {
            status { isForbidden() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:admission-mismatch") }
        }
    }

    @Test
    fun a_token_for_another_performance_is_forbidden() {
        val token = admit(buyer.id, otherPerformanceId)

        hold(seatIds.take(1), token).andExpect {
            status { isForbidden() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:admission-mismatch") }
        }
    }

    @Test
    fun the_gate_stands_only_in_front_of_holding() {
        // 조회·결제는 관문을 안 지난다(ADR 0004) — 결제는 이미 좌석을 쥔 사람이다.
        mvc.get("/api/reservations/1") { with(user(buyer)) }
            .andExpect { status { isNotFound() } }
    }

    /** 줄에 세우고 바로 들인다. 줄이 비어 있으면 진입이 곧 입장이다(`D12` 「항상 켠다」) */
    private fun admit(accountId: Long, performance: Long): String {
        queue.enter(performance, accountId)
        return requireNotNull(admission.tokenFor(performance, accountId)) { "진입했는데 토큰이 없다" }
    }

    private fun hold(seats: List<Long>, token: String?, key: String = UUID.randomUUID().toString()): ResultActionsDsl =
        mvc.post("/api/performances/$performanceId/reservations") {
            with(user(buyer))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("seat_ids" to seats))
            header("Idempotency-Key", key)
            token?.let { header(QueueGateFilter.ADMISSION_HEADER, it) }
        }

    private fun principal(email: String): TicketUser =
        TicketUser(fixture.account(email), email, AccountRole.AUDIENCE, passwordHash = null, active = true)

    private companion object {
        const val PREFIX = "gate-"
    }
}
