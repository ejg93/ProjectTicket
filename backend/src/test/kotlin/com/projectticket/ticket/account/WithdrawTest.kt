package com.projectticket.ticket.account

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.LoginAttemptService
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 탈퇴가 **비밀번호를 다시 받는가**(`44c`, `D9`·`D17`). 열린 세션만으로 계정을 없애면 자리를 비운 사이 누가 누른다.
 *
 * 틀리면 로그인과 같은 401 `login-failed` 이고 **탈퇴가 안 된다.** 틀린 시도는 로그인 실패 카운터를 센다 — 안 세면 무차별 대입의 우회 경로다.
 * 세션은 실제 로그인으로 받는다 — 저장소가 Redis 라 흉내 낸 세션을 앱이 안 본다(`LoginFlowTest`).
 */
class WithdrawTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient

    private lateinit var login: Cookie

    @BeforeEach
    fun setUp() {
        mvc.post("/api/auth/signup") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$EMAIL","password":"$PASSWORD","display_name":"관객","consents":{"terms_of_service":true,"privacy_collect":true}}"""
        }.andExpect { status { isCreated() } }
        login = mvc.post("/api/auth/login") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$EMAIL","password":"$PASSWORD"}"""
        }.andReturn().response.getCookie("TICKETSESSION") ?: throw AssertionError("로그인 응답에 세션 쿠키가 없다")
    }

    @Test
    fun a_wrong_password_is_login_failed_and_nothing_is_withdrawn() {
        withdraw("not-my-password-at-all").andExpect {
            status { isUnauthorized() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:login-failed") }
        }

        assertThat(withdrawn()).isFalse()
        // 로그인 실패와 같은 이름으로 시도가 남는다 — 탈퇴는 롤백돼도 이것은 별도 트랜잭션이다.
        assertThat(
            jdbc.sql(
                """
                select count(*) from audit_log
                 where event_type = 'account.login_failed' and detail->>'reason' = 'withdraw_bad_credentials'
                   and actor_account_id = (select account_id from account where email = :email)
                """,
            ).param("email", EMAIL).query(Long::class.java).single(),
        ).isEqualTo(1)
        // 세션도 그대로다 — 틀린 한 번이 로그아웃이 되면 안 된다.
        mvc.get("/api/me") { cookie(login) }.andExpect { status { isOk() } }
    }

    @Test
    fun the_right_password_withdraws_and_ends_the_session() {
        withdraw(PASSWORD).andExpect { status { isNoContent() } }

        assertThat(withdrawn()).isTrue()
        mvc.get("/api/me") { cookie(login) }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun wrong_passwords_count_toward_the_login_lock() {
        repeat(LoginAttemptService.MAX_ATTEMPTS) { withdraw("guess-$it-wrong-password") }

        // 잠긴 뒤에는 맞는 비밀번호도 같은 401 이다 — 로그인과 같은 카운터라서다.
        withdraw(PASSWORD).andExpect { status { isUnauthorized() } }
        assertThat(withdrawn()).isFalse()
        // 거꾸로도 잰다 — 탈퇴에서 쌓인 실패가 로그인을 막는다. 카운터가 따로면 탈퇴 입구가 대입 시도의 우회로가 된다(마무리 14차).
        mvc.post("/api/auth/login") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$EMAIL","password":"$PASSWORD"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun a_blank_password_is_a_validation_failure() {
        mvc.delete("/api/me") {
            cookie(login); with(csrf())
            contentType = MediaType.APPLICATION_JSON
            // 칸이 아예 없으면 역직렬화에서 `malformed-request` 다(Kotlin 비널). 빈 값이 `@NotBlank` 에 걸린다.
            content = """{"password":""}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:validation-failed") }
        }
    }

    private fun withdraw(password: String): ResultActionsDsl =
        mvc.delete("/api/me") {
            cookie(login); with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"password":"$password"}"""
        }

    private fun withdrawn(): Boolean =
        jdbc.sql("select deleted_at is not null from account where email = :email").param("email", EMAIL)
            .query(Boolean::class.java).single()

    private companion object {
        const val EMAIL = "withdraw-confirm@test.local"
        const val PASSWORD = "hunter2-and-then-some"
    }
}
