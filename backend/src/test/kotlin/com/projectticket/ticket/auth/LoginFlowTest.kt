package com.projectticket.ticket.auth

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

/**
 * 가입 → 로그인 → 내 정보 → 로그아웃 관통 흐름과, 로그인이 흘리면 안 되는 것들.
 *
 * 닫힘 조건(PLAN 청크 3). 로그인 실패 카운터는 여기 없다 — Redis 가 청크 21 에 온다(`3a`).
 */
class LoginFlowTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var sessionRegistry: SessionRegistry
    @Autowired lateinit var json: ObjectMapper

    @Test
    fun signup_login_me_logout() {
        signUp(EMAIL, PASSWORD).andExpect { status { isCreated() }; jsonPath("$.account_id") { isNumber() } }

        val logged = logIn(EMAIL, PASSWORD)
            .andExpect {
                status { isOk() }
                jsonPath("$.email") { value(EMAIL) }
                jsonPath("$.role") { value("audience") }
            }
            .andReturn().sessionOf()

        mvc.get("/api/me") { session = logged }
            .andExpect {
                status { isOk() }
                jsonPath("$.display_name") { value("관객") }
                jsonPath("$.role") { value("audience") }
            }

        mvc.post("/api/auth/logout") { session = logged; with(csrf()) }.andExpect { status { isNoContent() } }

        mvc.get("/api/me") { session = logged }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun unauthenticated_gets_problem_with_challenge() {
        mvc.get("/api/me").andExpect {
            status { isUnauthorized() }
            header { string("WWW-Authenticate", "Session") }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("tag:projectticket.example,2026:unauthenticated") }
        }
    }

    @Test
    fun wrong_password_and_unknown_email_look_the_same() {
        signUp(EMAIL, PASSWORD)
        val wrongPassword = identityOf(logIn(EMAIL, "wrong-but-long-enough-1"))
        val unknownEmail = identityOf(logIn("nobody@test.local", PASSWORD))
        // 둘을 가르면 그 이메일이 가입돼 있다는 것을 알려 주는 것이다(`D9`).
        assertThat(wrongPassword).isEqualTo(unknownEmail)
        assertThat(wrongPassword).contains("login-failed")
        // 핸들러가 내는 401 도 챌린지를 단다(RFC 9110 §15.5.2) — 진입점만 달면 절반만 지키는 것이다.
        logIn(EMAIL, "wrong-but-long-enough-1").andExpect { header { string("WWW-Authenticate", "Session") } }
    }

    @Test
    fun login_changes_session_id_and_erases_password_hash() {
        signUp(EMAIL, PASSWORD)
        val planted = MockHttpSession()
        val plantedId = planted.id

        val result = mvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$EMAIL","password":"$PASSWORD"}"""
            session = planted
            with(csrf())
        }.andReturn()

        val after = result.sessionOf()
        // 심어 둔 ID 가 그대로면 공격자가 그 ID 로 인증된 세션을 얻는다(세션 고정).
        assertThat(after.id).isNotEqualTo(plantedId)

        val context = after.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext
        val principal = checkNotNull(context.authentication).principal as TicketUser
        // 세션이 사는 내내 해시가 메모리에 남으면 안 된다.
        assertThat(principal.password).isNull()
        // 레지스트리 등록. 탈퇴 청크가 여기서 세션을 찾아 만료시킨다 — 지금 부르는 곳은 없고 자리만 있다(SecurityConfig).
        assertThat(sessionRegistry.allPrincipals.filterIsInstance<TicketUser>().any { it.id == principal.id }).isTrue()
    }

    @Test
    fun suspended_account_is_cut_on_next_request() {
        signUp(EMAIL, PASSWORD)
        val logged = logIn(EMAIL, PASSWORD).andReturn().sessionOf()
        jdbc.sql("update account set status = 'suspended' where email = :email").param("email", EMAIL).update()

        // 로그인 때만 보면 이미 로그인한 기기가 안 막힌다. 요청마다 생존을 본다. 필터가 끊는 401 도 챌린지·본문을 단다.
        mvc.get("/api/me") { session = logged }.andExpect {
            status { isUnauthorized() }
            header { string("WWW-Authenticate", "Session") }
            jsonPath("$.type") { value("tag:projectticket.example,2026:unauthenticated") }
        }
        // 정지된 계정의 새 로그인도 일반 실패와 같은 문구다.
        logIn(EMAIL, PASSWORD).andExpect {
            status { isUnauthorized() }
            header { string("WWW-Authenticate", "Session") }
            jsonPath("$.type") { value("tag:projectticket.example,2026:login-failed") }
        }
    }

    @Test
    fun login_resets_session_creation_time() {
        signUp(EMAIL, PASSWORD)
        val planted = MockHttpSession()
        val plantedCreation = planted.creationTime
        Thread.sleep(5)

        val after = mvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$EMAIL","password":"$PASSWORD"}"""
            session = planted
            with(csrf())
        }.andReturn().sessionOf()

        // 절대 만료 12시간은 로그인 시점부터다. changeSessionId 만으로는 생성 시각이 안 바뀐다.
        assertThat(after.creationTime).isGreaterThan(plantedCreation)
    }

    @Test
    fun duplicate_email_is_conflict_case_insensitively() {
        signUp(EMAIL, PASSWORD)
        signUp(EMAIL.uppercase(), PASSWORD).andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:email-taken") }
        }
    }

    @Test
    fun short_password_is_rejected_with_field_errors() {
        signUp(EMAIL, "short").andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:validation-failed") }
            jsonPath("$.errors[0].field") { value("password") }
        }
    }

    private fun signUp(email: String, password: String) = mvc.post("/api/auth/signup") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"email":"$email","password":"$password","display_name":"관객"}"""
        with(csrf())
    }

    private fun logIn(email: String, password: String) = mvc.post("/api/auth/login") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"email":"$email","password":"$password"}"""
        with(csrf())
    }

    private fun MvcResult.sessionOf(): MockHttpSession = request.getSession(false) as MockHttpSession

    /**
     * 응답이 무엇을 말하는가. 본문을 통째로 비교하면 `trace_id` 가 요청마다 달라서 무조건 갈린다 —
     * 여기서 볼 것은 「두 실패가 같은 것을 말하나」고 그 답은 `type` 과 `detail` 이다(`D5`).
     */
    private fun identityOf(actions: ResultActionsDsl): String {
        val body = json.readTree(actions.andReturn().response.contentAsString)
        return "${body["type"].asString()}|${body["detail"].asString()}"
    }

    private companion object {
        const val EMAIL = "login@test.local"
        const val PASSWORD = "hunter2-and-then-some"
    }
}
