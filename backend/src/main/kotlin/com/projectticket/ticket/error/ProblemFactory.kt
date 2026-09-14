package com.projectticket.ticket.error

import io.micrometer.tracing.Tracer
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component

/**
 * RFC 9457 본문을 만든다.
 *
 * [ApiExceptionHandler] 밖에도 쓰는 곳이 있어서 뺐다 — 인증 실패(401)는 MVC 에 닿기 전에 보안 필터가 끊어서
 * 예외 처리기가 못 잡는다. 두 자리가 각자 본문을 만들면 같은 오류가 형태만 다르게 두 벌 나간다.
 */
@Component
class ProblemFactory(private val tracer: Tracer) {

    /** @param detail 이 자리에서만 쓰는 설명. null 이면 [ErrorCode] 의 기본 문구를 쓴다 */
    fun create(code: ErrorCode, detail: String?, request: HttpServletRequest): ProblemDetail =
        ProblemDetail.forStatusAndDetail(code.status, detail ?: code.title).apply {
            type = URI.create(code.type)
            title = code.title
            // 요청 경로가 URI 문법에 안 맞으면 instance 를 비운다. 오류 처리기 안에서 던지면 마지막 그물까지 무너진다.
            instance = runCatching { URI(null, null, request.requestURI, null) }.getOrNull()
            // 오류 본문에만 넣는다(`D10`). 성공 응답에 넣으면 모든 응답이 커지는데 되짚어 볼 일이 있는 것은 실패한 요청이다.
            setProperty("trace_id", currentTraceId())
        }

    /**
     * 지금 요청의 추적 ID. `traceparent` 헤더를 직접 파싱하지 않는다 — 추적기가 이미 그 헤더를 읽어 MDC 와 로그에 넣고 있어서,
     * 여기서 또 읽으면 같은 사실을 두 군데서 정하게 된다. 헤더가 깨졌을 때 어느 쪽이 이기는지도 갈리고,
     * 그러면 **사용자가 불러 준 ID 로 로그를 찾았는데 안 나온다**(`D10` 「값을 만드는 곳은 하나다」).
     *
     * 추적 문맥이 없는 자리(기동·배치)에서 난 오류는 비운다. 지어내 봐야 어느 로그와도 안 이어진다.
     */
    private fun currentTraceId(): String? = tracer.currentSpan()?.context()?.traceId()
}
