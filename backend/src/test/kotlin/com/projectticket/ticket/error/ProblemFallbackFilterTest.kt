package com.projectticket.ticket.error

import io.micrometer.tracing.Tracer
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper

/**
 * 필터 안에서 던진 예외도 `problem+json` 으로 나가나(`I16-4`). `@ExceptionHandler` 는 필터 예외를 못 잡아서
 * 세션·계정 생존·관문 필터의 DB·Redis 예외가 Boot 기본 오류 본문으로 나갔다.
 *
 * 빠른 레인이다 — 필터를 직접 세우고 던지는 체인을 건넨다. 진짜 Redis 를 죽이는 시험은 컨테이너를 같이 쓰는 다른 시험을 흔든다.
 */
class ProblemFallbackFilterTest {

    private val filter = ProblemFallbackFilter(ProblemWriter(ProblemFactory(Tracer.NOOP), JsonMapper()))

    @Test
    fun a_dead_redis_in_a_filter_is_503_with_retry_after() {
        val response = run(FilterChain { _, _ -> throw RedisConnectionFailureException("down") })

        assertThat(response.status).isEqualTo(503)
        assertThat(response.contentType).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        assertThat(response.contentAsString).contains("queue-unavailable")
        assertThat(response.getHeader("Retry-After")).isEqualTo(ProblemWriter.RETRY_AFTER_SECONDS)
    }

    @Test
    fun a_wrapped_redis_failure_is_still_503() {
        // 세션 저장소는 Redis 예외를 감싸서 던질 수 있다 — 원인 사슬을 본다.
        val response = run(FilterChain { _, _ -> throw IllegalStateException("session", RedisConnectionFailureException("down")) })

        assertThat(response.status).isEqualTo(503)
        assertThat(response.contentAsString).contains("queue-unavailable")
    }

    @Test
    fun anything_else_is_500_internal() {
        val response = run(FilterChain { _, _ -> throw IllegalStateException("db down") })

        assertThat(response.status).isEqualTo(500)
        assertThat(response.contentType).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        assertThat(response.contentAsString).contains("internal").doesNotContain("db down")
    }

    @Test
    fun a_committed_response_is_rethrown() {
        // 이미 나간 응답에 본문을 덧쓰면 두 응답이 섞인다 — 컨테이너에 맡긴다.
        val response = MockHttpServletResponse()

        assertThatThrownBy {
            filter.doFilter(MockHttpServletRequest("GET", "/api/me"), response, FilterChain { _, res ->
                res.flushBuffer()
                error("late")
            })
        }.hasMessage("late")
    }

    private fun run(chain: FilterChain): MockHttpServletResponse =
        MockHttpServletResponse().also { filter.doFilter(MockHttpServletRequest("GET", "/api/me"), it, chain) }
}
