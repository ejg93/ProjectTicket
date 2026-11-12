package com.projectticket.ticket.error

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 필터에서 던진 예외의 그물(`I16-4`). [ApiExceptionHandler] 는 MVC 안의 예외만 잡아서 세션·계정 생존·관문 필터의
 * DB·Redis 예외가 Boot 기본 오류 본문으로 나갔다 — `problem+json` 이 아니고(`D5`) 503 도 `Retry-After` 도 없었다.
 *
 * 순서는 요청 로그([com.projectticket.ticket.observability.RequestLogFilter], +10) 안쪽, 세션(`SessionRepositoryFilter`, +50)·
 * 보안 필터 바깥이다 — 세션 저장소가 Redis 라 Redis 가 죽으면 세션 필터에서 먼저 터진다.
 *
 * - 원인 사슬에 Redis 연결 실패가 있으면 503 `queue-unavailable` + `Retry-After` — 관문의 503 과 같은 답이다
 * - 그 밖은 500 `internal`. 원인은 응답에 안 담고 로그에 스택까지 남긴다(`D9`)
 * - 응답이 이미 나갔으면 덧쓰지 않고 다시 던진다 — 두 응답이 섞인다
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
            if (e.causedByRedisDown()) {
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

    private fun Throwable.causedByRedisDown(): Boolean = generateSequence(this) { it.cause }.any { it is RedisConnectionFailureException }
}
