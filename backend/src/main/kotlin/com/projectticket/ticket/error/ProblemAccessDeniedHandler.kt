package com.projectticket.ticket.error

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * 인증은 됐는데 권한이 없을 때의 403 본문. CSRF 토큰이 틀린 것도 여기로 온다(`CsrfException` 이 `AccessDeniedException` 이다).
 *
 * 없으면 Spring 기본 `sendError(403)` 이 나가서 이것만 `type` 없는 본문이 된다.
 */
@Component
class ProblemAccessDeniedHandler(
    private val problems: ProblemFactory,
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, e: AccessDeniedException) {
        val problem = problems.create(ErrorCode.FORBIDDEN, null, request)
        response.status = ErrorCode.FORBIDDEN.status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"
        objectMapper.writeValue(response.writer, problem)
    }
}
