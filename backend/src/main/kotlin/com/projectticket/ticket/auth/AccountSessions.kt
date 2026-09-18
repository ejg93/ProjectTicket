package com.projectticket.ticket.auth

import org.springframework.security.core.session.SessionRegistry
import org.springframework.stereotype.Component

/**
 * 한 계정의 세션을 끊는 자리(`D9` 「세션 만료」). 정지(5b)·탈퇴(5a)가 쓴다.
 *
 * **인스턴스를 넘어 먹는다**(20a). 레지스트리가 Redis 를 읽어서 다른 대에 붙은 세션도 같이 만료되고,
 * 그 세션의 다음 요청은 `ConcurrentSessionFilter` 가 401 로 끊는다(`SecurityConfig`).
 *
 * 세션은 계정 **이름**(이메일)으로 색인돼 있다 — Spring Session 이 `SecurityContext` 의 인증에서 그 값을 뽑는다.
 */
@Component
class AccountSessions(private val sessionRegistry: SessionRegistry) {

    /**
     * 이미 만료된 것은 세지 않는다(`includeExpiredSessions = false`).
     *
     * @return 이번에 끊은 세션 수
     */
    fun expireAll(email: String): Int {
        val sessions = sessionRegistry.getAllSessions(email, false)
        sessions.forEach { it.expireNow() }
        return sessions.size
    }
}
