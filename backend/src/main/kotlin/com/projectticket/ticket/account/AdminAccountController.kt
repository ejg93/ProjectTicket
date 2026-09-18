package com.projectticket.ticket.account

import com.projectticket.ticket.auth.AccountSessions
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 관리자 입구(5b). 역할은 경로 규칙이 본다(`SecurityConfig.ADMIN_PREFIX`) — 컨트롤러가 역할을 다시 안 센다.
 *
 * **세션 끊기는 커밋 뒤다.** 정지가 롤백됐는데 세션만 날아가면 멀쩡한 사람이 로그아웃된다.
 */
@RestController
class AdminAccountController(
    private val suspensions: AccountSuspensionService,
    private val sessions: AccountSessions,
) {

    /** 정지 즉시 그 계정의 세션이 인스턴스를 넘어 전부 끊긴다(20a) */
    @PostMapping("/api/admin/accounts/{accountId}/suspend")
    fun suspend(@PathVariable accountId: Long, @AuthenticationPrincipal admin: TicketUser): Suspension {
        val email = suspensions.suspend(admin.id, accountId)
        return Suspension(accountId, "suspended", sessions.expireAll(email))
    }

    /** 푸는 것은 세션을 안 돌려준다 — 다시 로그인한다 */
    @PostMapping("/api/admin/accounts/{accountId}/resume")
    fun resume(@PathVariable accountId: Long, @AuthenticationPrincipal admin: TicketUser): Suspension {
        suspensions.resume(admin.id, accountId)
        return Suspension(accountId, "active", 0)
    }

    data class Suspension(val accountId: Long, val status: String, val cutSessionCount: Int)
}
