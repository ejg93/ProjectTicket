package com.projectticket.ticket.auth

import com.projectticket.ticket.PostgresTestBase
import jakarta.servlet.Filter
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.web.FilterChainProxy
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.util.ReflectionUtils
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 진짜 CSRF 경로 — 쿠키로 받은 토큰을 헤더에 실어 보낸다.
 *
 * 다른 테스트가 쓰는 `SecurityMockMvcRequestPostProcessors.csrf()` 는 테스트 전용 저장소를 지나가므로
 * 우리 구성(`CookieCsrfTokenRepository` + XOR 핸들러)을 한 번도 안 밟는다. 그 구성을 재는 자리가 여기다.
 */
class CsrfCookieTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var springSecurityFilterChain: Filter
    @Autowired lateinit var csrfTokenRepository: CsrfTokenRepository

    /**
     * 우리 저장소를 필터에 되돌린다.
     *
     * **`csrf()` 후처리기가 공유 필터 체인의 저장소를 바꿔 끼우고 그 바꿔치기가 컨텍스트에 남는다.**
     * 그래서 `csrf()` 를 쓰는 테스트 클래스가 먼저 돈 순서에서는 `CookieCsrfTokenRepository` 가 아예 안 돌고,
     * 이 테스트가 「쿠키가 없다」로 깨진다 — 2026-09-14 에 간헐로 세 번 났고 실패 문구에 저장소 이름을 실어서 잡았다(`stack.md`).
     *
     * **클래스 순서에 기대지 않는다.** 여기서 되돌리면 이 테스트가 무엇을 재는지가 순서와 무관해진다.
     */
    @BeforeEach
    fun restoreOurCsrfRepository() = csrfFilters().forEach { repositoryField().set(it, csrfTokenRepository) }

    @Test
    fun get_issues_cookie_and_header_from_it_passes() {
        // 상태부터 본다. 응답이 다른 이유로 틀어졌는데 쿠키만 단언하면 「쿠키가 없다」로 잘못 보고된다.
        val response = mvc.get("/api/health").andExpect { status { isOk() } }.andReturn().response
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
            .describedAs("첫 GET 이 쿠키를 내려야 SPA 가 다음 POST 에 실을 수 있다. 이때 CsrfFilter 가 든 저장소: %s", repositoriesInUse())
            .contains("XSRF-TOKEN=")

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

    @Test
    fun header_token_that_does_not_match_the_cookie_is_forbidden() {
        val token = checkNotNull(mvc.get("/api/health").andReturn().response.getCookie("XSRF-TOKEN")).value

        mvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"nobody@test.local","password":"whatever-long-enough"}"""
            cookie(Cookie("XSRF-TOKEN", token))
            header("X-XSRF-TOKEN", "$token-tampered")
        }.andExpect { status { isForbidden() } }
    }

    private fun csrfFilters(): List<CsrfFilter> =
        (springSecurityFilterChain as FilterChainProxy).filterChains
            .flatMap { it.filters }
            .filterIsInstance<CsrfFilter>()

    /** `CsrfFilter` 에 저장소를 다시 넣는 공개 설정자가 없어서 필드를 직접 든다 */
    private fun repositoryField() = ReflectionUtils
        .findField(CsrfFilter::class.java, "tokenRepository")!!
        .apply { isAccessible = true }

    /** 실패했을 때 무엇이 물려 있었는지 말해 준다. 이 값이 위 `@BeforeEach` 를 세운 근거였다 */
    private fun repositoriesInUse(): String = runCatching {
        csrfFilters().joinToString { repositoryField().get(it)!!::class.java.simpleName }
    }.getOrElse { "못 읽음: ${it.message}" }
}
