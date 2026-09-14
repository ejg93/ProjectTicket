package com.projectticket.ticket.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import java.time.Duration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 세션을 만든 지 12시간이 지나면 끊는다(`D9`).
 *
 * 무활동 만료와 다른 축이다. `application.yml` 의 `session.timeout` 은 「마지막 요청으로부터 30분」이라 계속 쓰면 영원히 안 끝난다.
 * 절대 만료는 언제 시작했나를 보므로 활동과 무관하게 끊긴다 — 탈취된 세션이 무한히 살아 있는 것을 막는 것이 목적이다.
 * 서블릿 표준에 설정 자리가 없어서 코드로만 된다.
 *
 * 세션 생성 시각은 [HttpSession.getCreationTime] 이 이미 들고 있다. `changeSessionId()` 는 ID 만 바꾸고 생성 시각을 안 바꾸므로
 * [AuthController] 가 로그인 앞에서 기존 세션을 버리고 새로 만든다 — 그래야 12시간이 로그인 시점부터 센다.
 */
class AbsoluteSessionTimeoutFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val session = request.getSession(false)
        if (session != null && isTooOld(session)) {
            session.invalidate()
            // 세션만 버리면 이 요청의 나머지 구간이 아직 인증된 상태로 돈다.
            SecurityContextHolder.clearContext()
        }
        chain.doFilter(request, response)
    }

    private fun isTooOld(session: HttpSession): Boolean =
        System.currentTimeMillis() - session.creationTime > MAX_AGE.toMillis()

    companion object {
        val MAX_AGE: Duration = Duration.ofHours(12)
    }
}
