package com.projectticket.ticket.auth

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

/**
 * 무차별 대입을 늦춘다(`3a` 의 닫힘 조건, `D9`).
 *
 * **강제 지점이 테스트인 이유**는 카운터가 Redis 에 있어서 제약을 걸 자리가 없어서다.
 * 재는 것은 둘이다 — 막히나, 그리고 **막혔다는 사실이 새지 않나**. 두 번째가 더 중요하다:
 * 「잠겼습니다」라고 말하면 그 이메일이 가입돼 있다는 뜻이 된다.
 */
class LoginAttemptTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var redis: StringRedisTemplate
    @Autowired lateinit var attempts: LoginAttemptService
    @Autowired lateinit var json: ObjectMapper

    @BeforeEach
    fun setUp() {
        signUp()
    }

    @Test
    fun blocked_looks_like_ordinary_failure() {
        val ordinary = identityOf(logIn(WRONG))
        repeat(LoginAttemptService.MAX_ATTEMPTS) { logIn(WRONG) }

        // 맞는 비밀번호를 대도 막힌다. 그리고 그 응답이 일반 실패와 **한 글자도 다르지 않다**.
        val blocked = logIn(PASSWORD)
        blocked.andExpect { status { isUnauthorized() } }
        assertThat(identityOf(blocked)).isEqualTo(ordinary)
    }

    @Test
    fun the_counter_stops_at_the_threshold() {
        repeat(LoginAttemptService.MAX_ATTEMPTS - 1) { logIn(WRONG) }

        // 넷까지는 아직이다. 하나 일찍 막으면 오타 몇 번에 사람이 갇힌다.
        assertThat(attempts.isBlocked(EMAIL, IP)).isFalse()
        logIn(WRONG)
        assertThat(attempts.isBlocked(EMAIL, IP)).isTrue()
    }

    @Test
    fun a_good_password_clears_the_count() {
        repeat(LoginAttemptService.MAX_ATTEMPTS - 1) { logIn(WRONG) }
        logIn(PASSWORD).andExpect { status { isOk() } }

        // 안 지우면 오타 넷을 낸 사람이 다음 실수에 막힌다.
        repeat(LoginAttemptService.MAX_ATTEMPTS - 1) { logIn(WRONG) }
        assertThat(attempts.isBlocked(EMAIL, IP)).isFalse()
    }

    @Test
    fun the_lock_lets_go_on_its_own() {
        repeat(LoginAttemptService.MAX_ATTEMPTS) { logIn(WRONG) }

        // 사람이 푸는 자리를 안 만들었다 — TTL 이 푼다. 그 TTL 이 없으면 영원히 갇힌다.
        val ttl = redis.getExpire("login:fail:$EMAIL:$IP")
        assertThat(ttl).isBetween(1, LoginAttemptService.LOCK_DURATION.toSeconds())
    }

    @Test
    fun another_address_is_not_locked_out() {
        repeat(LoginAttemptService.MAX_ATTEMPTS) { logIn(WRONG) }

        // 이메일만 세면 남의 계정을 잠글 수 있다. 막히는 것은 그 자리에서 두드리는 쪽이다.
        assertThat(attempts.isBlocked(EMAIL, "203.0.113.99")).isFalse()
    }

    private fun signUp() =
        mvc.post("/api/auth/signup") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$EMAIL","password":"$PASSWORD","display_name":"관객","consents":{"terms_of_service":true,"privacy_collect":true}}"""
        }.andExpect { status { isCreated() } }

    private fun logIn(password: String): ResultActionsDsl =
        mvc.post("/api/auth/login") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$EMAIL","password":"$password"}"""
            with { request -> request.apply { remoteAddr = IP } }
        }

    /** 응답이 무엇을 말하는가. `trace_id` 는 요청마다 달라서 비교에서 뺀다(`LoginFlowTest` 와 같은 방식) */
    private fun identityOf(actions: ResultActionsDsl): String {
        val body = json.readTree(actions.andReturn().response.contentAsString)
        return "${body["type"].asString()}|${body["detail"].asString()}|${body["title"].asString()}"
    }

    private companion object {
        const val EMAIL = "attempt@test.local"
        const val PASSWORD = "hunter2-and-then-some"
        const val WRONG = "wrong-but-long-enough-1"
        const val IP = "203.0.113.10"
    }
}
