package com.projectticket.ticket.error

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * 인증이 없을 때의 401 본문.
 *
 * 보안 필터가 MVC 에 닿기 전에 요청을 끊어서 [ApiExceptionHandler] 가 못 잡는다. 여기서 직접 쓰지 않으면
 * 인증 실패만 본문 없이 상태 코드로 나가고 클라이언트는 그 하나만 다르게 처리해야 한다.
 */
@Component
class ProblemEntryPoint(
    private val problems: ProblemFactory,
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val problem = problems.create(ErrorCode.UNAUTHENTICATED, null, request)
        response.status = ErrorCode.UNAUTHENTICATED.status.value()
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE)
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"
        objectMapper.writeValue(response.writer, problem)
    }

    companion object {
        /**
         * 401 에 반드시 붙어야 하는 챌린지(RFC 9110 §15.5.2 — MUST send a WWW-Authenticate header field).
         *
         * 등록된 스킴(`Basic`·`Digest`)을 안 쓴다 — 브라우저가 기본 인증 대화상자를 띄워서 우리 로그인 화면을 가린다.
         * `Session` 은 IANA 에 등록된 이름이 아니라 브라우저가 무시하므로 팝업이 안 뜨고, 형식상 챌린지는 하나 있다.
         */
        const val CHALLENGE = "Session"
    }
}
