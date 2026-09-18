package com.projectticket.ticket.queue

import com.projectticket.ticket.auth.SecuredApiFilter
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.ProblemWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 문지기(`D12` 「관문」). **선점 요청 하나에만 걸린다** — 결제·취소·조회는 안 지난다(ADR 0004).
 * 결제는 이미 좌석을 쥔 사람이고, 조회는 읽기 모델이 받는다.
 *
 * 컨트롤러가 아니라 필터인 이유는 **입구가 늘어도 빠뜨릴 자리가 없어서**다. 선점 경로가 하나 더 생겨도
 * 여기 경로 규칙만 늘리면 된다 — 서비스에 검사를 넣으면 새 입구가 그것을 안 부른다.
 *
 * 성공한 선점은 토큰을 **반납한다**(`D12` 「토큰」). 좌석을 잡았으면 문지기는 볼일이 끝났고, 반납해야 정원이 돈다.
 * 실패는 반납하지 않는다 — 남이 먼저 잡은 좌석을 골랐을 뿐이라 TTL 안에서 다른 좌석을 고를 수 있어야 한다.
 */
@Component
class QueueGateFilter(
    private val admission: AdmissionService,
    private val queue: QueueService,
    private val problems: ProblemWriter,
) : OncePerRequestFilter(), SecuredApiFilter {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = performanceIdOf(request) == null

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val performanceId = performanceIdOf(request) ?: return chain.doFilter(request, response)
        // 로그인하지 않았으면 인가 필터가 이미 401 을 냈다. 여기 오는 요청은 세션이 있다.
        val user = SecurityContextHolder.getContext().authentication?.principal as? TicketUser
            ?: return chain.doFilter(request, response)

        val token = request.getHeader(ADMISSION_HEADER)
        val admitted = try {
            token?.let(admission::find)
        } catch (e: RuntimeException) {
            // Redis 가 죽었다. **관문을 닫는다** — 열어 두면 대기열이 막으려던 폭발이 그대로 DB 로 간다(`D12`).
            logger.warn("대기열 관문이 Redis 를 못 본다 — 선점을 막는다 이유=${e.javaClass.simpleName}")
            return problems.write(
                request,
                response,
                ErrorCode.QUEUE_UNAVAILABLE,
                headers = mapOf("Retry-After" to RETRY_AFTER_SECONDS),
            )
        }

        if (admitted == null) {
            // 판매 중이 아닌 회차는 줄이 없어서 토큰을 받을 길이 없다. 그런 요청은 관문이 아니라 선점 서비스가 답한다(409·410).
            // **DB 를 보는 것은 이 실패 경로뿐**이다 — 토큰이 맞으면 관문은 Redis 만 보고 지나간다.
            if (!queue.isGated(performanceId)) return chain.doFilter(request, response)

            // 헤더가 없거나 만료·위조된 토큰이다. 둘을 안 가른다 — 가르면 「그 토큰은 있었다」를 알려 주는 것이다(`D9`).
            return problems.write(request, response, ErrorCode.ADMISSION_REQUIRED, waitingProperties(performanceId, user.id))
        }
        if (admitted.accountId != user.id || admitted.performanceId != performanceId) {
            return problems.write(request, response, ErrorCode.ADMISSION_MISMATCH)
        }

        chain.doFilter(request, response)

        // 선점이 만들어졌을 때만 반납한다(201, `D5`).
        if (response.status == CREATED) {
            admission.release(performanceId, user.id)
        }
    }

    /** 429 본문에 순번을 실어 화면이 바로 줄을 보여 준다(`D5` 「type 목록」). 줄에 없으면 순번도 없다 */
    private fun waitingProperties(performanceId: Long, accountId: Long): Map<String, Any> =
        runCatching { queue.position(performanceId, accountId) }
            .map { position ->
                buildMap<String, Any> {
                    position.rank?.let { put("rank", it) }
                    position.etaSeconds?.let { put("eta_seconds", it) }
                }
            }
            .getOrDefault(emptyMap())

    /** 선점 경로에서만 회차 id 가 나온다. 다른 요청은 [shouldNotFilter] 가 걸러 낸다 */
    private fun performanceIdOf(request: HttpServletRequest): Long? {
        if (!HttpMethod.POST.matches(request.method)) return null
        return HOLD_PATH.matchEntire(request.requestURI)?.groupValues?.get(1)?.toLongOrNull()
    }

    companion object {
        /** 쿠키가 아니라 헤더다 — 회차 여럿에 줄 선 사람의 토큰이 섞이면 안 된다(`D12`) */
        const val ADMISSION_HEADER = "X-Admission-Token"

        private val HOLD_PATH = Regex("""/api/performances/(\d+)/reservations""")
        private const val CREATED = 201
        private const val RETRY_AFTER_SECONDS = "5"
    }
}

/**
 * 빈으로 두면 Boot 가 **서블릿 체인에도** 등록해서 보안 밖에서 한 번 더 돈다(`AccountLivenessFilter` 의 같은 함정).
 * 등록만 끄고 빈은 남긴다 — [com.projectticket.ticket.auth.SecurityConfig] 가 인가 뒤에 끼운다.
 */
@Configuration(proxyBeanMethods = false)
class QueueGateRegistration {

    @Bean
    fun queueGateFilterRegistration(filter: QueueGateFilter): FilterRegistrationBean<QueueGateFilter> =
        FilterRegistrationBean(filter).apply { isEnabled = false }
}
