package com.projectticket.ticket.auth

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.session.Session
import org.springframework.session.SessionRepository
import org.springframework.session.web.http.CookieSerializer
import tools.jackson.databind.ObjectMapper

/**
 * 가입 → 로그인 → 내 정보 → 로그아웃 관통 흐름과, 로그인이 흘리면 안 되는 것들.
 *
 * 닫힘 조건(PLAN 청크 3). 로그인 실패 카운터는 여기 없다 — Redis 가 청크 21 에 온다(`3a`).
 *
 * 세션을 쿠키로 잇는다. 저장소가 Redis 가 된 뒤로(`20a`) `MockHttpSession` 을 심어도 앱은 그것을 안 본다 —
 * 브라우저가 하는 그대로 응답이 내린 쿠키를 다음 요청에 싣고, 세션 속을 볼 때는 저장소에서 꺼낸다.
 */
class LoginFlowTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var sessionRegistry: SessionRegistry
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var sessions: SessionRepository<out Session>
    @Autowired lateinit var cookieSerializer: CookieSerializer

    @Test
    fun signup_login_me_logout() {
        signUp(EMAIL, PASSWORD).andExpect { status { isCreated() }; jsonPath("$.account_id") { isNumber() } }

        val logged = logIn(EMAIL, PASSWORD)
            .andExpect {
                status { isOk() }
                jsonPath("$.email") { value(EMAIL) }
                jsonPath("$.role") { value("audience") }
            }
            .andReturn().sessionCookie()

        mvc.get("/api/me") { cookie(logged) }
            .andExpect {
                status { isOk() }
                jsonPath("$.display_name") { value("관객") }
                jsonPath("$.role") { value("audience") }
            }

        mvc.post("/api/auth/logout") { cookie(logged); with(csrf()) }.andExpect { status { isNoContent() } }

        mvc.get("/api/me") { cookie(logged) }.andExpect { status { isUnauthorized() } }
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
        // 공격자가 미리 쥔 세션 자리에 로그인 요청을 태운다. 한 번 로그인해 두고 그 쿠키를 다시 쓰는 것이 그 모양이다.
        val planted = logIn(EMAIL, PASSWORD).andReturn().sessionCookie()
        val plantedId = sessionIdOf(planted)

        val after = mvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$EMAIL","password":"$PASSWORD"}"""
            cookie(planted)
            with(csrf())
        }.andReturn().sessionCookie()

        // 심어 둔 ID 가 그대로면 공격자가 그 ID 로 인증된 세션을 얻는다(세션 고정).
        assertThat(sessionIdOf(after)).isNotEqualTo(plantedId)
        // 저장소에서도 사라져야 한다. 남으면 그 ID 가 아직 먹는다.
        assertThat(sessions.findById(plantedId)).isNull()

        val context = checkNotNull(
            storedSession(after).getAttribute<SecurityContext>(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY),
        )
        val principal = checkNotNull(context.authentication).principal as TicketUser
        // 세션이 사는 내내 해시가 메모리에 남으면 안 된다. Redis 에 직렬화된 것을 다시 읽은 값이라 저장소까지 포함해서 본 것이다.
        assertThat(principal.password).isNull()
        // 계정으로 세션을 찾는다. 정지·탈퇴(`5a`·`5b`)가 여기서 세션을 골라 만료시킨다 — `getAllPrincipals()` 는 이 구현이 못 한다.
        assertThat(sessionRegistry.getAllSessions(principal, false)).isNotEmpty()
    }

    @Test
    fun suspended_account_is_cut_on_next_request() {
        signUp(EMAIL, PASSWORD)
        val logged = logIn(EMAIL, PASSWORD).andReturn().sessionCookie()
        jdbc.sql("update account set status = 'suspended' where email = :email").param("email", EMAIL).update()

        // 로그인 때만 보면 이미 로그인한 기기가 안 막힌다. 요청마다 생존을 본다. 필터가 끊는 401 도 챌린지·본문을 단다.
        mvc.get("/api/me") { cookie(logged) }.andExpect {
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
        val planted = logIn(EMAIL, PASSWORD).andReturn().sessionCookie()
        val plantedCreation = storedSession(planted).creationTime
        Thread.sleep(5)

        val after = mvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$EMAIL","password":"$PASSWORD"}"""
            cookie(planted)
            with(csrf())
        }.andReturn().sessionCookie()

        // 절대 만료 12시간은 로그인 시점부터다. changeSessionId 만으로는 생성 시각이 안 바뀐다.
        assertThat(storedSession(after).creationTime).isAfter(plantedCreation)
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
        content = """{"email":"$email","password":"$password","display_name":"관객","consents":{"terms_of_service":true,"privacy_collect":true}}"""
        with(csrf())
    }

    private fun logIn(email: String, password: String) = mvc.post("/api/auth/login") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"email":"$email","password":"$password"}"""
        with(csrf())
    }

    /** 응답이 내린 세션 쿠키. 다음 요청에 이것을 실어야 같은 세션으로 이어진다 */
    private fun MvcResult.sessionCookie(): Cookie =
        response.getCookie(SESSION_COOKIE) ?: throw AssertionError("응답에 $SESSION_COOKIE 쿠키가 없다")

    /** 쿠키 값은 인코딩돼 있다. 저장소에서 찾으려면 발급한 것과 같은 직렬화기로 푼다 */
    private fun sessionIdOf(cookie: Cookie): String =
        cookieSerializer.readCookieValues(MockHttpServletRequest().apply { setCookies(cookie) }).single()

    private fun storedSession(cookie: Cookie): Session =
        sessions.findById(sessionIdOf(cookie)) ?: throw AssertionError("저장소에 세션이 없다")

    /**
     * 응답이 무엇을 말하는가. 본문을 통째로 비교하면 `trace_id` 가 요청마다 달라서 무조건 갈린다 —
     * 여기서 볼 것은 「두 실패가 같은 것을 말하나」고 그 답은 `type` 과 `detail` 이다(`D5`).
     */
    private fun identityOf(actions: ResultActionsDsl): String {
        val body = json.readTree(actions.andReturn().response.contentAsString)
        return "${body["type"].asString()}|${body["detail"].asString()}"
    }

    private companion object {
        const val SESSION_COOKIE = "TICKETSESSION"
        const val EMAIL = "login@test.local"
        const val PASSWORD = "hunter2-and-then-some"
    }
}
