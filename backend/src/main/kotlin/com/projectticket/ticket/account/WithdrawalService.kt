package com.projectticket.ticket.account

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.auth.LoginAttemptService
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 탈퇴(5a, `D9` + 개인정보보호법 제21조).
 *
 * **지우지 않고 수명을 끊는다.** `deleted_at` 만 찍고 개인정보는 유예 뒤에 [AccountPurgeBatch] 가 지운다 —
 * 예매·결제·정산이 계정을 외래키로 잡고 있어서 행을 지우면 산 사람의 기록까지 끌려간다(`D2`).
 *
 * 로그인은 그 즉시 막힌다(`TicketUserDetailsService` 의 `deleted_at is null`), 이미 붙어 있는 세션은
 * 부르는 쪽이 커밋 뒤에 끊는다([MeController]).
 *
 * **비밀번호를 다시 받는다**(`44c`, `D17` 지도 「비밀번호를 다시 받는다」). 열린 세션만으로 계정을 없애면 자리를 비운 사이 누가 누른다.
 * 틀리면 로그인과 **같은 401 `login-failed`** 다 — 탈퇴 입구가 따로 말하면 대조 결과를 다른 이름으로 흘린다(`D9`).
 * **로그인 실패 카운터를 같이 건다**(`3a`) — 같은 비밀번호 대조라 안 걸면 무차별 대입의 우회 경로가 된다.
 */
@Service
class WithdrawalService(
    private val jdbc: JdbcClient,
    private val auditLog: AuditLog,
    private val passwordEncoder: PasswordEncoder,
    private val loginAttempts: LoginAttemptService,
) {

    /** @return 그 계정의 이메일. 세션을 끊을 때 쓴다 — 세션 색인이 이름(이메일)이다 */
    @Transactional
    fun withdraw(accountId: Long, password: String, ip: String): String {
        confirmPassword(accountId, password, ip)

        // 조건부라 두 번 눌러도 둘째는 0행이다. 이미 나간 사람을 다시 내보내면 `deleted_at` 이 뒤로 밀려 유예가 늘어난다.
        val email = jdbc.sql(
            "update account set deleted_at = now() where account_id = :id and deleted_at is null returning email",
        )
            .param("id", accountId)
            .query(String::class.java)
            .optional()
            .orElse(null)
            ?: throw TicketException(ErrorCode.ACCOUNT_NOT_FOUND, "이미 탈퇴했거나 없는 계정이다: account_id=$accountId")

        auditLog.record(
            AuditLog.Kind.OUTCOME,
            "account.withdrawn",
            accountId,
            AuditLog.Target.of("account", accountId),
        )
        return email
    }

    private fun confirmPassword(accountId: Long, password: String, ip: String) {
        val account = jdbc.sql("select email, password_hash from account where account_id = :id and deleted_at is null")
            .param("id", accountId)
            .query(Credentials::class.java)
            .optional()
            .orElseThrow { TicketException(ErrorCode.ACCOUNT_NOT_FOUND, "이미 탈퇴했거나 없는 계정이다: account_id=$accountId") }

        // 막혀 있으면 대조도 안 한다 — 로그인과 같은 순서다. 막혔다고 따로 말하지 않는다(`D9`).
        if (loginAttempts.isBlocked(account.email, ip)) throw TicketException(ErrorCode.LOGIN_FAILED)
        if (!passwordEncoder.matches(password, account.passwordHash)) {
            loginAttempts.recordFailure(account.email, ip)
            throw TicketException(ErrorCode.LOGIN_FAILED)
        }
        loginAttempts.reset(account.email, ip)
    }

    private data class Credentials(val email: String, val passwordHash: String)
}
