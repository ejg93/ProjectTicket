package com.projectticket.ticket.error

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 필터에서 던진 예외의 그물(`I16-4`). [ApiExceptionHandler] 는 MVC 안의 예외만 잡아서 세션·계정 생존·관문 필터의
 * DB·Redis 예외가 Boot 기본 오류 본문으로 나갔다 — `problem+json` 이 아니고(`D5`) 503 도 `Retry-After` 도 없었다.
 *
 * 순서는 요청 로그([com.projectticket.ticket.observability.RequestLogFilter], +10) 안쪽, 세션(`SessionRepositoryFilter`, +50)·
 * 보안 필터 바깥이다 — 세션 저장소가 Redis 라 Redis 가 죽으면 세션 필터에서 먼저 터진다.
 *
 * - 원인 사슬에 Redis 장애가 있으면([isRedisDown]) 503 `queue-unavailable` + `Retry-After` — 관문의 503 과 같은 답이다
 * - 그 밖은 500 `internal`. 원인은 응답에 안 담고 로그에 스택까지 남긴다(`D9`)
 * - 응답이 이미 나갔으면 덧쓰지 않고 다시 던진다 — 두 응답이 섞인다
 *
 * 컨트롤러가 성공한 뒤 세션 저장·토큰 반납이 Redis 로 터지면 응답이 아직 안 나갔으면 그 201 이 503 으로 덮인다.
 * 선점·결제는 멱등키가 재시도를 같은 답으로 받는다. `reset()` 이라 앞서 쓴 헤더(보안 헤더·`Set-Cookie`·`Location`)도 지운다 —
 * 실패한 처리의 `Location` 을 남기지 않는 쪽을 골랐다(마무리 34차). 500·503 은 기본 캐시 대상이 아니다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class ProblemFallbackFilter(private val problems: ProblemWriter) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(ProblemFallbackFilter::class.java)

    @Suppress("TooGenericExceptionCaught") // 마지막 그물이다 — 무엇이 오든 problem 본문으로 바꾸는 것이 이 필터의 일이다
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        try {
            chain.doFilter(request, response)
        } catch (e: Exception) {
            if (response.isCommitted) throw e
            response.reset()
            if (e.isRedisDown()) {
                log.warn("Redis 연결 실패로 요청을 못 받았다: {} {}", request.method, request.requestURI, e)
                problems.write(
                    request = request,
                    response = response,
                    code = ErrorCode.QUEUE_UNAVAILABLE,
                    headers = mapOf("Retry-After" to ProblemWriter.RETRY_AFTER_SECONDS),
                )
            } else {
                log.error("필터에서 처리하지 못한 예외: {} {}", request.method, request.requestURI, e)
                problems.write(request, response, ErrorCode.INTERNAL)
            }
        }
    }
}
