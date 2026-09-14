package com.projectticket.ticket.auth

import com.projectticket.ticket.PostgresTestBase
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
        val cookie = mvc.get("/api/health").andReturn().response.getCookie("XSRF-TOKEN")
        assertThat(cookie).describedAs("첫 GET 이 쿠키를 내려야 SPA 가 다음 POST 에 실을 수 있다").isNotNull

        mvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"nobody@test.local","password":"whatever-long-enough"}"""
            cookie(Cookie("XSRF-TOKEN", cookie!!.value))
            header("X-XSRF-TOKEN", cookie.value)
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
