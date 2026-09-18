package com.projectticket.ticket.account

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.jdbc.core.simple.JdbcClient
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
 */
@Service
class WithdrawalService(
    private val jdbc: JdbcClient,
    private val auditLog: AuditLog,
) {

    /** @return 그 계정의 이메일. 세션을 끊을 때 쓴다 — 세션 색인이 이름(이메일)이다 */
    @Transactional
    fun withdraw(accountId: Long): String {
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
}
