package com.projectticket.ticket.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 요청 하나가 지나간 흔적을 한 줄 남긴다(`D10`).
 *
 * 시작과 끝을 두 줄로 안 쓴다. 끝 줄에 걸린 시간이 있으면 시작 줄이 답하는 것이 「요청이 왔다」뿐인데 그건 끝 줄로도 안다.
 * 두 줄이면 로그가 두 배가 되고 뒤엉킨 로그에서 짝을 찾는 일이 는다. 요청이 끝나지 않는 경우를 봐야 할 때 그때 시작 줄을 켠다.
 *
 * 본문은 안 찍는다(`D10`). 로그인 본문에 비밀번호가, 가입 본문에 이름이 있다. 쿼리 문자열도 뺀다 — 검색어·이메일이 그리로 들어온다.
 *
 * 추적 ID 는 여기서 안 붙인다. Micrometer Tracing 이 MDC 에 넣고 로그 패턴이 집어간다.
 *
 * ## 자리가 좁다
 *
 * 보안 필터보다 바깥, 추적 필터보다 안쪽이어야 한다. 순서를 안 주면 기본이 맨 안쪽이라 두 가지가 동시에 어긋난다.
 *
 * - 보안 필터 안쪽이면 **401·403 이 한 줄도 안 남는다** — 거부된 요청은 여기까지 안 온다. 정작 되짚어 볼 일이 많은 것이 거부와 실패다
 * - 추적 필터 바깥이면 추적 문맥이 아직 없거나 이미 닫혀서 이 줄에만 추적 ID 가 빈다
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RequestLogFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val startedAt = System.nanoTime()
        var thrown: Throwable? = null
        try {
            chain.doFilter(request, response)
        } catch (e: Throwable) {
            // 예외로 빠져나가도 그 요청이 무엇이었는지는 남아야 한다 — 정작 실패한 요청의 흔적이 없으면 로그를 왜 남기는지 모르게 된다.
            thrown = e
            throw e
        } finally {
            // **예외가 여기까지 오면 상태 코드가 아직 안 정해졌다.** 컨테이너가 오류 페이지를 만들면서 그때 500 을 쓰므로
            // 이 자리에서 읽으면 200 이다. 그래서 상태 대신 예외 이름을 찍는다 — 「200 으로 실패했다」는 줄보다 낫다.
            log.info(
                "{} {} {} {}ms",
                request.method,
                request.requestURI,
                thrown?.let { "실패(${it.javaClass.simpleName})" } ?: response.status,
                (System.nanoTime() - startedAt) / 1_000_000,
            )
        }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        SILENT_PREFIXES.any { request.requestURI.startsWith(it) }

    private companion object {
        val log = LoggerFactory.getLogger(RequestLogFilter::class.java)

        /**
         * 이 경로들은 안 찍는다. 컨테이너 헬스체크가 `/actuator/health` 를 30초마다 두드린다 —
         * 남겨 두면 하루 2,880줄이 쌓여서 진짜 요청이 그 사이에 묻힌다.
         */
        val SILENT_PREFIXES = listOf("/actuator/")
    }
}
