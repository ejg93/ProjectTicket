package com.projectticket.ticket.error

import org.springframework.http.HttpStatus

/**
 * 이 서비스가 내는 오류의 목록. 한 곳에 모아 두는 것이 이 enum 의 목적이다 —
 * 흩어지면 「우리가 몇 가지 오류를 내나」에 답하려고 패키지를 뒤져야 하고, `D5` 의 상태 코드 표와 대조할 수 없다.
 *
 * `type` 이 계약이다. 프론트는 상태 코드가 아니라 `type` 으로 분기한다(`D5`). 상태 코드는 여러 오류가 공유하지만
 * `type` 은 하나를 가리킨다. 이 값을 바꾸면 화면이 깨진다 — 문구(`title`)는 다듬어도 슬러그는 못 바꾼다.
 *
 * `tag:` URI 다(RFC 4151). 없는 도메인을 가리키지 않으려는 것이고, RFC 9457 이 `type` 의 역참조를 요구하지 않아서 그래도 된다.
 * `projectticket.example` 은 RFC 2606 의 예시용 이름이라 남의 것을 가리킬 위험이 없다.
 */
enum class ErrorCode(val status: HttpStatus, val slug: String, val title: String) {

    // 인증
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "login-failed", "이메일 또는 비밀번호가 맞지 않는다"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthenticated", "로그인이 필요하다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden", "할 수 있는 권한이 없다"),

    // 가입
    EMAIL_TAKEN(HttpStatus.CONFLICT, "email-taken", "이미 가입된 이메일이다"),

    // 요청 형식. 프레임워크가 정한 상태 코드를 우리 type 으로 옮길 때 쓴다 — 하나로 뭉치면
    // 405·415·깨진 JSON 이 같은 type 으로 나가서 상태 코드보다 type 이 더 뭉친다.
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "validation-failed", "요청 형식이 맞지 않는다"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "malformed-request", "요청을 읽지 못했다"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "이 경로에서 쓸 수 없는 메서드다"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type", "받을 수 없는 본문 형식이다"),
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "endpoint-not-found", "그런 경로가 없다"),

    // 마지막 그물. 원인은 로그에만 남긴다.
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "요청을 처리하지 못했다");

    val type: String get() = "tag:projectticket.example,2026:$slug"
}
