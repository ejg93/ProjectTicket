package com.projectticket.ticket.auth

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 계정을 만든다. 역할은 관객으로 고정이다 — 기획사·관리자는 가입 경로로 못 얻는다.
 *
 * 동의 사건·감사 기록은 청크 4 가 이 트랜잭션에 붙인다. 계정만 생기고 동의가 빠지면 동의 없이 개인정보를 든 계정이 된다.
 */
@Service
class SignupService(private val jdbc: JdbcClient, private val passwordEncoder: PasswordEncoder) {

    data class Command(val email: String, val password: String, val displayName: String)

    @Transactional
    fun signUp(command: Command): Long =
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
