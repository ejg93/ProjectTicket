package com.projectticket.ticket.auth

import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 요청마다 계정이 아직 살아 있는지 본다.
 *
 * 로그인할 때만 보면 이미 로그인한 다른 기기가 안 막힌다 — 탈퇴·정지된 계정으로 다른 브라우저에서 계속 쓸 수 있다는 뜻이다.
 *
 * 요청마다 DB 를 한 번 친다. ProjectShop 은 권한 캐시에 얹었지만 여기는 캐시가 아직 없다 — 부하 청크(31)가 이 자리를 재고
 * 비싸면 Redis 캐시(청크 21 뒤)로 옮긴다.
 *
 * 빈으로 두지 않는다. `@Component` 를 붙이면 Boot 가 서블릿 필터로도 등록해서 보안 체인 밖에서 한 번 더 돈다.
 */
class AccountLivenessFilter(private val jdbc: JdbcClient) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val principal = SecurityContextHolder.getContext().authentication?.principal
        // 인증이 없으면 볼 것이 없다. 익명 요청은 그대로 흘려보내고 인가 필터가 판단한다.
        if (principal is TicketUser && !isAlive(principal.id)) {
            expire(request)
            response.status = HttpStatus.UNAUTHORIZED.value()
            return
        }
        chain.doFilter(request, response)
    }

    private fun isAlive(accountId: Long): Boolean =
        jdbc.sql("select exists(select 1 from account where account_id = :id and deleted_at is null and status = 'active')")
            .param("id", accountId)
            .query(Boolean::class.java)
            .single()

    /** 죽은 계정의 세션을 그 자리에서 버린다. 401 만 주고 세션을 두면 다음 요청마다 같은 조회가 다시 돈다 */
    private fun expire(request: HttpServletRequest) {
        SecurityContextHolder.clearContext()
        request.getSession(false)?.invalidate()
    }
}
