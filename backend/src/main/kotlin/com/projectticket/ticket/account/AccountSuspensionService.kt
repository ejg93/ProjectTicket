package com.projectticket.ticket.account

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.auth.AccountStatus
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자가 계정을 정지하고 푼다(5b, `D9`).
 *
 * **상태만 바꾸고 세션은 안 끊는다.** 끊는 것은 커밋 뒤에 [AdminAccountController] 가 한다 —
 * 트랜잭션 안에서 끊으면 롤백된 정지가 남의 세션을 날리고, 세션 저장소에는 되돌릴 방법이 없다.
 *
 * 탈퇴한 계정은 대상이 아니다. 그쪽은 `deleted_at` 이 답하고 로그인 자체가 막혀 있다(`TicketUserDetailsService`).
 */
@Service
class AccountSuspensionService(
    private val jdbc: JdbcClient,
    private val auditLog: AuditLog,
) {

    /** @return 그 계정의 이메일. 세션을 끊을 때 쓴다 — 세션 색인이 이름(이메일)이다 */
    @Transactional
    fun suspend(actorAccountId: Long, accountId: Long): String =
        move(actorAccountId, accountId, to = AccountStatus.SUSPENDED, from = AccountStatus.ACTIVE, event = "account.suspended")

    @Transactional
    fun resume(actorAccountId: Long, accountId: Long): String =
        move(actorAccountId, accountId, to = AccountStatus.ACTIVE, from = AccountStatus.SUSPENDED, event = "account.resumed")

    /**
     * 조건부 UPDATE 다. 두 관리자가 같이 눌러도 둘째는 0행이고, 그때는 **이미 그 상태**라 409 가 아니라 실패다 —
     * 어느 쪽인지는 계정을 다시 읽어 가른다(없는 계정과 같은 상태를 다르게 말해야 한다).
     */
    private fun move(actorAccountId: Long, accountId: Long, to: AccountStatus, from: AccountStatus, event: String): String {
        val email = jdbc.sql(
            """
            update account
               set status = :to
             where account_id = :id and status = :from and deleted_at is null
            returning email
            """,
        )
            .param("to", to.code)
            .param("from", from.code)
            .param("id", accountId)
            .query(String::class.java)
            .optional()
            .orElse(null)
            ?: throw cannotMove(accountId, to)

        auditLog.record(
            AuditLog.Kind.OUTCOME,
            event,
            actorAccountId,
            AuditLog.Target.of("account", accountId),
            mapOf("status" to to.code),
        )
        return email
    }

    private fun cannotMove(accountId: Long, to: AccountStatus): TicketException {
        val status = jdbc.sql("select status from account where account_id = :id and deleted_at is null")
            .param("id", accountId)
            .query(String::class.java)
            .optional()
            .orElse(null) ?: return TicketException(ErrorCode.ACCOUNT_NOT_FOUND, "그런 계정이 없다: account_id=$accountId")

        return TicketException(
            ErrorCode.INVALID_TRANSITION,
            "이미 그 상태다: status=$status",
            mapOf("from" to status, "action" to to.code),
        )
    }
}
