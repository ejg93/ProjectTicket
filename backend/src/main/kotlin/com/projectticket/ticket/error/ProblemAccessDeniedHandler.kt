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
        // **경로 접두가 오류를 가른다**(`D5` 「기획사·관리자 경로는 접두로 가른다」). 역할 때문에 막힌 것과 CSRF 실패를 화면이 갈라 읽어야 한다 —
        // `error` 패키지가 경로 규약을 하나 드는 대가로, 입구마다 역할을 다시 확인하는 줄이 사라진다.
        val code = if (request.requestURI.startsWith(ORGANIZER_PREFIX)) ErrorCode.ORGANIZER_FORBIDDEN else ErrorCode.FORBIDDEN
        val problem = problems.create(code, null, request)
        response.status = code.status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"
        objectMapper.writeValue(response.writer, problem)
    }

    companion object {
        /** `SecurityConfig.ORGANIZER_PREFIX` 의 와일드카드를 뗀 것 — 그쪽이 막고 이쪽이 이름을 붙인다 */
        const val ORGANIZER_PREFIX = "/api/organizer/"
    }
}
