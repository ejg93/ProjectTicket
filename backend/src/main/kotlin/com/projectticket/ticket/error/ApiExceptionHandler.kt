package com.projectticket.ticket.error

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * 도메인 예외를 HTTP 로 옮기는 유일한 자리(`D14`).
 *
 * 여기 말고 상태 코드를 정하는 곳이 생기면 `D5` 규약과 대조할 대상이 흩어진다. 서비스는 [TicketException] 만 던지고
 * 번역은 전부 이 클래스가 한다.
 *
 * [ResponseEntityExceptionHandler] 를 상속한다. Spring 이 프레임워크 예외(본문 파싱 실패, 지원 안 하는 메서드·미디어 타입,
 * 검증 실패)를 자기 핸들러로 먼저 잡는데, 상속하지 않으면 그것들만 우리 형식을 안 타고 나간다.
 */
@RestControllerAdvice
class ApiExceptionHandler(private val problems: ProblemFactory) : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(TicketException::class)
    fun handle(e: TicketException, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        respond(problems.create(e.code, e.message, request).apply { e.properties.forEach { (name, value) -> setProperty(name, value) } })

    /** 메서드 보안이 던지는 것. 안 잡으면 아래 마지막 그물이 500 으로 삼킨다 */
    @ExceptionHandler(AccessDeniedException::class)
    fun handle(e: AccessDeniedException, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        respond(problems.create(ErrorCode.FORBIDDEN, null, request))

    @ExceptionHandler(AuthenticationException::class)
    fun handle(e: AuthenticationException, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        respond(problems.create(ErrorCode.UNAUTHENTICATED, null, request))

    /**
     * Bean Validation 실패. 어느 필드가 왜 틀렸는지를 담는다 — 「요청 형식이 맞지 않는다」만 주면
     * 클라이언트가 어디를 고쳐야 할지 몰라서 사람이 눈으로 찾게 된다.
     */
    override fun handleMethodArgumentNotValid(
        e: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = problems.create(ErrorCode.VALIDATION_FAILED, null, servletRequestOf(request))
        problem.setProperty(
            "errors",
            e.bindingResult.fieldErrors.map { FieldError(toSnakeCase(it.field), it.defaultMessage) },
        )
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status).body(problem)
    }

    /** @param field 요청 본문의 필드 이름. Jackson 이 snake_case 로 내보내므로 여기서 맞춘다(`D5`) */
    data class FieldError(val field: String, val message: String?)

    /**
     * 나머지 프레임워크 예외. 깨진 JSON, 지원 안 하는 메서드·미디어 타입 같은 것들이다.
     * Spring 이 만든 본문을 버리고 우리 것으로 갈아 끼운다 — 상태 코드는 그쪽 판단이 맞지만 `type` 이 없으면
     * 프론트가 이것들만 다르게 분기해야 한다.
     *
     * 본문의 `status` 는 실제 HTTP 상태와 같아야 한다(RFC 9457 §3.1). 우리 [ErrorCode] 표에 없는 상태(406·413·503)는
     * 가장 가까운 무리의 `type` 을 달되 `status` 는 프레임워크 값을 그대로 쓴다.
     */
    override fun createResponseEntity(
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        if (body is ProblemDetail && body.properties == null) {
            val ours = problems.create(frameworkCodeOf(statusCode), body.detail, servletRequestOf(request))
            ours.status = statusCode.value()
            return super.createResponseEntity(ours, headers, statusCode, request)
        }
        return super.createResponseEntity(body, headers, statusCode, request)
    }

    /**
     * 마지막 그물. 여기까지 온 것은 우리가 예상 못 한 것이다. 원인을 응답에 안 담는다 — 스택이나 SQL 문구가 나가면
     * 그 자체가 정보 유출이다(`D9`). 원인은 스택까지 로그에 남긴다.
     */
    @ExceptionHandler(Exception::class)
    fun handle(e: Exception, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        log.error("처리하지 못한 예외: {} {}", request.method, request.requestURI, e)
        return respond(problems.create(ErrorCode.INTERNAL, null, request))
    }

    /** 401 은 반드시 챌린지를 단다(RFC 9110 §15.5.2). 진입점과 같은 값이다 — 두 자리가 갈리면 한쪽이 표준을 어긴다 */
    private fun respond(problem: ProblemDetail): ResponseEntity<ProblemDetail> {
        val builder = ResponseEntity.status(problem.status)
        if (problem.status == HttpStatus.UNAUTHORIZED.value()) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, ProblemEntryPoint.CHALLENGE)
        }
        return builder.body(problem)
    }

    private fun frameworkCodeOf(status: HttpStatusCode): ErrorCode = when {
        status.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED) -> ErrorCode.METHOD_NOT_ALLOWED
        status.isSameCodeAs(HttpStatus.UNSUPPORTED_MEDIA_TYPE) -> ErrorCode.UNSUPPORTED_MEDIA_TYPE
        status.isSameCodeAs(HttpStatus.NOT_FOUND) -> ErrorCode.ENDPOINT_NOT_FOUND
        status.is5xxServerError -> ErrorCode.INTERNAL
        else -> ErrorCode.MALFORMED_REQUEST
    }

    private fun servletRequestOf(request: WebRequest): HttpServletRequest =
        (request as ServletWebRequest).request

    private fun toSnakeCase(camel: String): String =
        camel.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
}
