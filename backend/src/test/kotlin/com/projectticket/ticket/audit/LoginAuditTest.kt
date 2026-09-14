package com.projectticket.ticket.audit

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * 실패한 로그인이 남는가, 그리고 그 기록에 개인정보가 안 담기는가.
 *
 * **실패가 남는 것이 이 테스트의 요지다.** 실패한 요청은 예외로 끝나므로, 같은 트랜잭션에 담으면 롤백과 함께 사라진다 —
 * 정작 남겨야 할 것이 그것이다([AuditLog.Kind.ATTEMPT]).
 */
class LoginAuditTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun failed_login_survives_the_rollback() {
        logIn("nobody@test.local", "wrong-but-long-enough")

        val rows = jdbc.sql("select detail::text from audit_log where event_type = 'account.login_failed'")
            .query(String::class.java).list()

        assertThat(rows).hasSize(1)
        // 이메일을 안 담는다(`D10`). 감사 로그는 파기 예외라 오래 남는데 가입도 안 한 사람의 이메일이 3년을 남을 이유가 없다.
        assertThat(rows.single()).doesNotContain("nobody@test.local").contains("bad_credentials")
    }

    @Test
    fun successful_login_is_recorded_with_the_account_id() {
        val accountId = jdbc.sql(
            """
            insert into account (email, password_hash, display_name)
            values ('audit@test.local', :hash, '관객')
            returning account_id
            """,
        ).param("hash", passwordEncoder.encode(PASSWORD)).query(Long::class.java).single()

        logIn("audit@test.local", PASSWORD)

        assertThat(
            jdbc.sql("select actor_account_id from audit_log where event_type = 'account.logged_in'")
                .query(Long::class.java).list(),
        ).containsExactly(accountId)
    }

    private fun logIn(email: String, password: String) = mvc.post("/api/auth/login") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"email":"$email","password":"$password"}"""
        with(csrf())
    }

    private companion object {
        const val PASSWORD = "hunter2-and-then-some"
    }
}
