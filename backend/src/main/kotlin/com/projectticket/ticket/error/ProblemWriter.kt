package com.projectticket.ticket.error

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * RFC 9457 본문을 응답에 **직접 쓴다**. 필터가 요청을 끊을 때 쓰는 자리다 —
 * 보안 필터 바깥에서 난 거절은 [ApiExceptionHandler] 가 못 잡는다.
 *
 * 이 클래스가 있는 이유는 상태 코드와 본문 형식을 `error` 패키지 안에 묶어 두려는 것이다(`D14`·`ArchitectureTest`).
 * 필터마다 `ProblemDetail` 을 만들면 오류 형식이 패키지마다 조금씩 달라지고, 그때부터 화면이 자리마다 다르게 읽는다.
 */
@Component
class ProblemWriter(
    private val problems: ProblemFactory,
    private val objectMapper: ObjectMapper,
) {

    /**
     * @param properties 그 오류에만 붙는 값(`D5` 「type 목록」) — 관문의 `rank`·`eta_seconds` 같은 것
     * @param headers 상태 코드가 요구하는 헤더 — 503 의 `Retry-After` 같은 것
     */
    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        code: ErrorCode,
        properties: Map<String, Any> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ) {
        val problem = problems.create(code, null, request)
        properties.forEach(problem::setProperty)
        response.status = code.status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"
        headers.forEach(response::setHeader)
        objectMapper.writeValue(response.writer, problem)
    }
}
