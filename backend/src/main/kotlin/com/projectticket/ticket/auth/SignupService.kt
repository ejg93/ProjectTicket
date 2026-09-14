package com.projectticket.ticket.auth

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.consent.ConsentService
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 계정을 만들고 그 자리에서 동의 사건을 남긴다. 역할은 관객으로 고정이다 — 기획사·관리자는 가입 경로로 못 얻는다.
 *
 * **둘이 한 트랜잭션인 이유가 있다.** 계정만 생기고 동의가 빠지면 동의 없이 개인정보를 들고 있는 계정이 되고,
 * 그 상태를 나중에 알아볼 방법이 없다. 반대로 동의만 남고 계정이 없으면 주인 없는 기록이 된다.
 */
@Service
class SignupService(
    private val jdbc: JdbcClient,
    private val passwordEncoder: PasswordEncoder,
    private val consentService: ConsentService,
    private val auditLog: AuditLog,
) {

    /** @param consents 항목 코드 → 동의 여부. **담긴 것만** 기록한다 */
    data class Command(
        val email: String,
        val password: String,
        val displayName: String,
        val consents: Map<String, Boolean?>,
        val actorIp: String?,
    )

    @Transactional
    fun signUp(command: Command): Long {
        val items = consentService.currentItems()
        consentService.verify(command.consents, items)

        val accountId = insertAccount(command)
        consentService.record(accountId, command.consents, items, source = "signup", actorIp = command.actorIp)

        auditLog.record(
            AuditLog.Kind.OUTCOME,
            "account.signed_up",
            accountId,
            AuditLog.Target.of("account", accountId),
            mapOf("consent_count" to command.consents.size),
        )
        return accountId
    }

    private fun insertAccount(command: Command): Long =
        // 이메일 중복을 미리 조회해서 막지 않는다. 조회와 삽입 사이에 남이 끼어들 수 있어서
        // 어차피 유니크 인덱스가 최종 판단이다. 둘 다 두면 같은 규칙이 두 군데가 된다.
        try {
            jdbc.sql(
                """
                insert into account (email, password_hash, display_name, role)
                values (:email, :passwordHash, :displayName, :role)
                returning account_id
                """,
            )
                .param("email", command.email)
                .param("passwordHash", passwordEncoder.encode(command.password))
                .param("displayName", command.displayName)
                .param("role", AccountRole.AUDIENCE.code)
                .query(Long::class.java)
                .single()
        } catch (e: DuplicateKeyException) {
            throw TicketException(ErrorCode.EMAIL_TAKEN)
        }
}
