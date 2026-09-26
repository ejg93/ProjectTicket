package com.projectticket.ticket.auth

import com.projectticket.ticket.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get

/**
 * 공개 경로가 **메서드까지** 열렸나(`S1`, `security-baseline.md` 「노출면」).
 *
 * 전에는 경로만 열어 같은 경로의 모든 메서드가 인가를 지났다 — 지금 그 경로에 DELETE 입구가 없어서 405 로 끝났을 뿐이다.
 */
class PublicPathMethodTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun a_write_on_a_public_read_path_needs_a_login() {
        // CSRF 없이 보내면 `ProblemAccessDeniedHandler` 가 403 을 먼저 낸다 — 인가까지 가게 토큰을 싣는다.
        mvc.delete("/api/events/1") { with(csrf()) }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun login_is_open_to_post_only() {
        // 전에는 인가를 지나 405 였다.
        mvc.get("/api/auth/login").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun a_public_read_stays_open() {
        mvc.get("/api/events").andExpect { status { isOk() } }
    }
}
