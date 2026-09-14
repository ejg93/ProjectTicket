package com.projectticket.ticket.auth

import com.projectticket.ticket.PostgresTestBase
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 진짜 CSRF 경로 — 쿠키로 받은 토큰을 헤더에 실어 보낸다. `with(csrf())` 는 테스트 전용 저장소라 이 구성을 안 지난다.
 */
class CsrfCookieTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun get_issues_cookie_and_header_from_it_passes() {
        // 상태부터 본다. 응답이 다른 이유로 틀어졌는데 쿠키만 단언하면 「쿠키가 없다」로 잘못 보고된다 —
        // 2026-09-14 에 이 테스트가 한 번 그렇게 깨졌고 무엇이 틀렸는지를 못 읽었다(`stack.md`).
        val response = mvc.get("/api/health").andExpect { status { isOk() } }.andReturn().response
        val issued = response.getHeader(HttpHeaders.SET_COOKIE)
        assertThat(issued).describedAs("첫 GET 이 쿠키를 내려야 SPA 가 다음 POST 에 실을 수 있다").contains("XSRF-TOKEN=")

        val token = checkNotNull(response.getCookie("XSRF-TOKEN")).value

        mvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"nobody@test.local","password":"whatever-long-enough"}"""
            cookie(Cookie("XSRF-TOKEN", token))
            header("X-XSRF-TOKEN", token)
        }.andExpect {
            // CSRF 를 지났다는 증거는 403 이 아니라 로그인 판정(401)까지 갔다는 것이다.
            status { isUnauthorized() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:login-failed") }
            header { string("WWW-Authenticate", "Session") }
        }
    }

    @Test
    fun post_without_token_is_forbidden_problem() {
        mvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"nobody@test.local","password":"whatever-long-enough"}"""
        }.andExpect {
            status { isForbidden() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("tag:projectticket.example,2026:forbidden") }
        }
    }
}
