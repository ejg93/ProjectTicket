package com.projectticket.ticket.error

import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component

/**
 * RFC 9457 본문을 만든다.
 *
 * [ApiExceptionHandler] 밖에도 쓰는 곳이 있어서 뺐다 — 인증 실패(401)는 MVC 에 닿기 전에 보안 필터가 끊어서
 * 예외 처리기가 못 잡는다. 두 자리가 각자 본문을 만들면 같은 오류가 형태만 다르게 두 벌 나간다.
 *
 * `trace_id` 는 아직 없다. 추적 ID 를 발급하는 것이 청크 5(관측 포팅)라 그때 이 자리에 붙는다.
 */
@Component
class ProblemFactory {

    /** @param detail 이 자리에서만 쓰는 설명. null 이면 [ErrorCode] 의 기본 문구를 쓴다 */
    fun create(code: ErrorCode, detail: String?, request: HttpServletRequest): ProblemDetail =
        ProblemDetail.forStatusAndDetail(code.status, detail ?: code.title).apply {
            type = URI.create(code.type)
            title = code.title
            instance = URI.create(request.requestURI)
        }
}
