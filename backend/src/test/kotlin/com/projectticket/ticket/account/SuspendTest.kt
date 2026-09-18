package com.projectticket.ticket.account

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 관리자 정지·해제(5b의 닫힘 조건, `D9`).
 *
 * **강제 지점이 테스트인 이유**는 세션이 Redis 에 있어서 제약을 걸 자리가 없어서다. 재는 것은 하나다 —
 * 정지가 **이미 로그인한 세션**을 끊나. 로그인할 때만 막으면 다른 기기에서 계속 쓴다.
 */
class SuspendTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var sessionRegistry: SessionRegistry

    private lateinit var admin: TicketUser
    private var targetId: Long = 0

    @BeforeEach
    fun setUp() {
        admin = principal("${PREFIX}admin@test.local", AccountRole.ADMIN)
        targetId = signUp()
    }

    @Test
    fun other_session_cut_immediately() {
        val session = logIn()
        mvc.get("/api/me") { cookie(session) }.andExpect { status { isOk() } }

        suspend().andExpect {
            status { isOk() }
            // 그 계정의 세션 하나를 끊었다. 0 이면 레지스트리가 세션을 못 찾은 것이고, 그때 정지는 반쪽이다.
            jsonPath("$.cut_session_count") { value(1) }
        }

        // 이미 로그인한 기기가 그 자리에서 막힌다. 다음 로그인만 막으면 정지가 늦는다.
        mvc.get("/api/me") { cookie(session) }.andExpect { status { isUnauthorized() } }
        assertThat(sessionRegistry.getAllSessions(EMAIL, false)).isEmpty()
    }

    @Test
    fun resuming_does_not_hand_the_session_back() {
        val session = logIn()
        suspend()

        mvc.post("/api/admin/accounts/$targetId/resume") { with(user(admin)); with(csrf()) }
            .andExpect { status { isOk() }; jsonPath("$.status") { value("active") } }

        // 상태는 돌아왔지만 끊긴 세션은 안 돌아온다 — 다시 로그인한다.
        mvc.get("/api/me") { cookie(session) }.andExpect { status { isUnauthorized() } }
        assertThat(statusOf()).isEqualTo("active")
    }

    @Test
    fun suspending_twice_is_rejected() {
        suspend().andExpect { status { isOk() } }

        suspend().andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:invalid-transition") }
            jsonPath("$.from") { value("suspended") }
        }
    }

    @Test
    fun an_unknown_account_is_not_found() {
        mvc.post("/api/admin/accounts/-1/suspend") { with(user(admin)); with(csrf()) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.type") { value("tag:projectticket.example,2026:account-not-found") }
            }
    }

    @Test
    fun an_audience_cannot_reach_the_admin_path() {
        val audience = principal("${PREFIX}audience@test.local", AccountRole.AUDIENCE)

        // 역할은 경로 규칙이 본다(`SecurityConfig`) — 컨트롤러에 닿기 전에 막힌다.
        mvc.post("/api/admin/accounts/$targetId/suspend") { with(user(audience)); with(csrf()) }
            .andExpect { status { isForbidden() } }
    }

    private fun suspend() =
        mvc.post("/api/admin/accounts/$targetId/suspend") { with(user(admin)); with(csrf()) }

    private fun signUp(): Long {
        mvc.post("/api/auth/signup") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$EMAIL","password":"$PASSWORD","display_name":"관객","consents":{"terms_of_service":true,"privacy_collect":true}}"""
        }.andExpect { status { isCreated() } }
        return jdbc.sql("select account_id from account where email = :email").param("email", EMAIL)
            .query(Long::class.java).single()
    }

    private fun logIn(): Cookie =
        mvc.post("/api/auth/login") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$EMAIL","password":"$PASSWORD"}"""
        }.andReturn().sessionCookie()

    private fun MvcResult.sessionCookie(): Cookie =
        response.getCookie("TICKETSESSION") ?: throw AssertionError("응답에 세션 쿠키가 없다")

    private fun statusOf(): String =
        jdbc.sql("select status from account where account_id = :id").param("id", targetId)
            .query(String::class.java).single()

    /** 관리자·기획사는 가입 경로로 못 얻는다(`V2`) — 시드처럼 직접 넣는다 */
    private fun principal(email: String, role: AccountRole): TicketUser {
        val id = jdbc.sql(
            "insert into account (email, password_hash, display_name, role) values (:email, 'x', '관리자', :role) returning account_id",
        ).param("email", email).param("role", role.code).query(Long::class.java).single()
        return TicketUser(id, email, role, passwordHash = null, active = true)
    }

    private companion object {
        const val PREFIX = "suspend-"
        const val EMAIL = "suspend-target@test.local"
        const val PASSWORD = "hunter2-and-then-some"
    }
}
