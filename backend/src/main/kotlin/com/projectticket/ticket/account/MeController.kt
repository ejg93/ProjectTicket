package com.projectticket.ticket.account

import com.projectticket.ticket.auth.AccountSessions
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 내 계정. 세션의 principal 이 가리키는 행을 읽는다 — 이메일·이름은 세션이 아니라 DB 가 진실이다(변경이 세션에 안 반영된다) */
@RestController
@RequestMapping("/api/me")
class MeController(
    private val jdbc: JdbcClient,
    private val withdrawals: WithdrawalService,
    private val sessions: AccountSessions,
) {

    @GetMapping
    fun me(@AuthenticationPrincipal user: TicketUser): MeResponse =
        jdbc.sql("select account_id, email, display_name, role from account where account_id = :id")
            .param("id", user.id)
            .query(MeResponse::class.java)
            .single()

    /**
     * 탈퇴(5a). 204 다 — 돌려줄 것이 없다(`D5`).
     *
     * **세션 끊기는 커밋 뒤다.** 탈퇴가 롤백됐는데 세션만 날아가면 멀쩡한 사람이 로그아웃된다.
     * 개인정보는 여기서 안 지운다 — 유예 30일 뒤에 [AccountPurgeBatch] 가 지운다.
     */
    @DeleteMapping
    fun withdraw(@AuthenticationPrincipal user: TicketUser): ResponseEntity<Void> {
        val email = withdrawals.withdraw(user.id)
        sessions.expireAll(email)
        return ResponseEntity.noContent().build()
    }

    data class MeResponse(val accountId: Long, val email: String, val displayName: String, val role: String)
}
