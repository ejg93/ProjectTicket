package com.projectticket.ticket.account

import com.projectticket.ticket.PostgresTestBase
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 탈퇴와 파기(5a의 닫힘 조건, `D9` + 개인정보보호법 제21조).
 *
 * 축이 둘이다 — **탈퇴는 즉시**(로그인·세션이 그 자리에서 끊긴다), **파기는 유예 뒤**(30일).
 * 유예 안에 지우면 되돌려 달라는 요청을 못 받고, 유예 뒤에 안 지우면 남길 이유 없는 개인정보를 든다.
 */
class AccountPurgeTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var purge: AccountPurgeBatch
    @Autowired lateinit var sessionRegistry: SessionRegistry

    private var accountId: Long = 0

    @BeforeEach
    fun setUp() {
        accountId = signUp()
    }

    @Test
    fun pii_nulled_after_grace() {
        withdraw(logIn())
        agedOut(days = AccountPurgeBatch.GRACE_DAYS + 1)

        assertThat(purge.purgeDue()).isEqualTo(1)

        // 행은 남는다 — 예매·결제가 이 계정을 외래키로 잡고 있고, 남겨야 할 것은 「누가」가 아니라 「무슨 일이 있었나」다.
        val row = identityOf()
        assertThat(row.email).isNull()
        assertThat(row.displayName).isNull()
        assertThat(row.passwordHash).isNull()
        assertThat(row.exists).isTrue()
    }

    @Test
    fun inside_the_grace_nothing_is_touched() {
        withdraw(logIn())
        agedOut(days = AccountPurgeBatch.GRACE_DAYS - 1)

        // 되돌려 달라는 요청이 오는 기간이다. 하루 일찍 지우면 그 요청에 답할 것이 없다.
        assertThat(purge.purgeDue()).isZero()
        assertThat(identityOf().email).isNotNull()
    }

    @Test
    fun a_live_account_is_never_purged() {
        agedOut(days = AccountPurgeBatch.GRACE_DAYS + 1)

        // 탈퇴하지 않았으면 `deleted_at` 이 null 이라 애초에 안 걸린다. 걸렸다면 DB 제약이 막는다(`V2`).
        assertThat(purge.purgeDue()).isZero()
        assertThat(identityOf().email).isNotNull()
    }

    @Test
    fun withdrawing_cuts_the_session_and_the_login() {
        val session = logIn()
        mvc.get("/api/me") { cookie(session) }.andExpect { status { isOk() } }

        withdraw(session)

        // 이미 붙어 있던 세션이 그 자리에서 끊긴다(20a — 인스턴스를 넘어 먹는다).
        mvc.get("/api/me") { cookie(session) }.andExpect { status { isUnauthorized() } }
        assertThat(sessionRegistry.getAllSessions(EMAIL, false)).isEmpty()
        // 새 로그인도 막힌다. 탈퇴 계정은 없는 계정과 같은 문구다(`D9`).
        mvc.post("/api/auth/login") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$EMAIL","password":"$PASSWORD"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun purging_twice_changes_nothing_more() {
        withdraw(logIn())
        agedOut(days = AccountPurgeBatch.GRACE_DAYS + 1)

        assertThat(purge.purgeDue()).isEqualTo(1)
        assertThat(purge.purgeDue()).isZero()
    }

    @Test
    fun old_audit_rows_go_with_it() {
        jdbc.sql(
            """
            insert into audit_log (event_type, actor_account_id, created_at)
            values ('account.logged_in', :id, now() - make_interval(years => :years))
            """,
        ).param("id", accountId).param("years", AccountPurgeBatch.AUDIT_RETENTION_YEARS + 1).update()
        val before = auditCount()

        purge.purgeDue()

        // 보존 3년(`D9`). 그 안쪽은 `audit_log` 의 가드 트리거가 삭제 자체를 막는다(`V3`).
        assertThat(auditCount()).isLessThan(before)
    }

    private fun agedOut(days: Int) =
        jdbc.sql(
            """
            update account
               set deleted_at = case when deleted_at is null then null else now() - make_interval(days => :days) end
             where account_id = :id
            """,
        ).param("days", days).param("id", accountId).update()

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

    private fun withdraw(session: Cookie) =
        mvc.delete("/api/me") { cookie(session); with(csrf()) }.andExpect { status { isNoContent() } }

    private fun MvcResult.sessionCookie(): Cookie =
        response.getCookie("TICKETSESSION") ?: throw AssertionError("응답에 세션 쿠키가 없다")

    private fun identityOf(): Identity =
        jdbc.sql(
            """
            select email, display_name, password_hash, true as exists
              from account where account_id = :id
            """,
        ).param("id", accountId).query(Identity::class.java).single()

    private fun auditCount(): Long =
        jdbc.sql("select count(*) from audit_log").query(Long::class.java).single()

    data class Identity(val email: String?, val displayName: String?, val passwordHash: String?, val exists: Boolean)

    private companion object {
        const val EMAIL = "withdraw@test.local"
        const val PASSWORD = "hunter2-and-then-some"
    }
}
