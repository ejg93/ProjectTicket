package com.projectticket.ticket.queue

import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.error.ApiExceptionHandler
import com.projectticket.ticket.error.ProblemFactory
import com.projectticket.ticket.error.ProblemWriter
import com.projectticket.ticket.observability.TicketMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.tracing.Tracer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper

/**
 * Redis 가 죽으면 관문이 **헤더 유무와 무관하게** 503 `queue-unavailable` 로 닫히나(`D12` 「장애」, `I11-2`).
 *
 * 전에는 토큰 헤더가 있을 때만 Redis 를 봐서, 헤더 없는 요청은 DB 의 회차 상태만 보고 429 `admission-required` 를 받았다 —
 * 「줄에 서면 된다」인데 줄 자체가 없다. 순번 조회의 실패는 `runCatching` 이 삼켰다. 이 경로를 아무 시험도 안 쟀다.
 *
 * 빠른 레인이다 — 필터를 직접 세우고 두 서비스를 모의로 바꿔 Redis 예외를 던지게 한다. 진짜 Redis 를 죽이는 시험은
 * 컨테이너를 같이 쓰는 다른 시험을 흔든다.
 */
class QueueGateRedisDownTest {

    private val admission: AdmissionService = mock(AdmissionService::class.java)
    private val queue: QueueService = mock(QueueService::class.java)
    private val filter = QueueGateFilter(
        admission = admission,
        queue = queue,
        problems = ProblemWriter(ProblemFactory(Tracer.NOOP), JsonMapper()),
        metrics = TicketMetrics(SimpleMeterRegistry()),
    )

    @BeforeEach
    fun signIn() {
        val user = TicketUser(id = ACCOUNT, email = "gate-down@test.local", role = AccountRole.AUDIENCE, passwordHash = null, active = true)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(user, null, user.authorities)
        `when`(queue.gateOf(PERFORMANCE)).thenReturn(QueueService.Gate.GATED)
    }

    @AfterEach
    fun signOut() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun without_a_token_a_dead_redis_closes_the_gate() {
        `when`(queue.position(anyLong(), anyLong())).thenThrow(RedisConnectionFailureException("down"))

        val response = hold(token = null)

        assertClosed(response)
    }

    @Test
    fun with_a_token_a_dead_redis_closes_the_gate() {
        `when`(admission.find(anyString())).thenThrow(RedisConnectionFailureException("down"))

        val response = hold(token = "any-token")

        assertClosed(response)
    }

    @Test
    fun a_dead_redis_behind_the_filters_is_503_too() {
        // 관문을 지난 뒤 MVC 안에서 Redis 가 죽어도 같은 503 이다 — 전에는 마지막 그물이 500 `internal` 로 냈다(`I16-4`).
        `when`(queue.position(anyLong(), anyLong())).thenThrow(RedisConnectionFailureException("down"))
        val mvc = MockMvcBuilders.standaloneSetup(QueueController(queue))
            .setControllerAdvice(ApiExceptionHandler(ProblemFactory(Tracer.NOOP)))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

        mvc.get("/api/queue/$PERFORMANCE").andExpect {
            status { isServiceUnavailable() }
            header { string(name = "Retry-After", value = ProblemWriter.RETRY_AFTER_SECONDS) }
            jsonPath("$.type") { value("tag:projectticket.example,2026:queue-unavailable") }
        }
    }

    private fun hold(token: String?): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", "/api/performances/$PERFORMANCE/reservations")
        token?.let { request.addHeader(QueueGateFilter.ADMISSION_HEADER, it) }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        assertThat(chain.request).describedAs("관문이 닫혔으면 선점까지 가면 안 된다").isNull()
        return response
    }

    private fun assertClosed(response: MockHttpServletResponse) {
        assertThat(response.status).isEqualTo(503)
        assertThat(response.contentAsString).contains("queue-unavailable")
        assertThat(response.getHeader("Retry-After")).isEqualTo("5")
    }

    private companion object {
        const val ACCOUNT = 42L
        const val PERFORMANCE = 7L
    }
}
