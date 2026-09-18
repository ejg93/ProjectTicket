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

    // 계정 관리(5b). 탈퇴한 계정도 **없는 것**이다 — 관리자에게도 마찬가지다(`D5` 「403 이냐 404 냐」).
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "account-not-found", "그런 계정이 없다"),

    // 동의. 형식은 맞는데 값이 규칙에 안 맞는 자리라 422 다(`D5`).
    UNKNOWN_CONSENT_ITEM(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-consent-item", "모르는 동의 항목이다"),
    REQUIRED_CONSENT_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "required-consent-missing", "필수 동의 항목이다"),

    // 공연·회차
    PERFORMANCE_NOT_FOUND(HttpStatus.NOT_FOUND, "performance-not-found", "그런 회차가 없다"),

    // 형식은 맞는데 지금 상태가 못 받는 것이라 422 다. 좌석이 없거나 등급이 안 붙은 구역이 있으면 열 수 없다.
    PERFORMANCE_NOT_OPENABLE(HttpStatus.UNPROCESSABLE_CONTENT, "performance-not-openable", "지금 열 수 없는 회차다"),

    // 기획사(11). 역할이 없으면 경로 규칙이 막고(`SecurityConfig`) 이름은 `ProblemAccessDeniedHandler` 가 붙인다.
    // 남의 기획사·공연은 **없는 것**이다 — 404(`D5` 「403 이냐 404 냐」).
    ORGANIZER_FORBIDDEN(HttpStatus.FORBIDDEN, "organizer-forbidden", "기획사 권한이 필요하다"),
    ORGANIZER_NOT_FOUND(HttpStatus.NOT_FOUND, "organizer-not-found", "그런 기획사가 없다"),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "event-not-found", "그런 공연이 없다"),
    // 같은 홀·같은 시각에 회차가 둘일 수 없다(`performance_hall_slot_key`). 형식이 아니라 지금 상태가 못 받는 것이라 409 다(`D5`).
    PERFORMANCE_SLOT_TAKEN(HttpStatus.CONFLICT, "performance-slot-taken", "그 홀의 그 시각에 회차가 이미 있다"),

    // 예매·선점(13). 추가 필드는 `D5` 「type 목록」이 정했다 — 화면이 그 값으로 「어느 좌석」을 표시한다.
    PERFORMANCE_NOT_OPEN(HttpStatus.CONFLICT, "performance-not-open", "판매 중인 회차가 아니다"),
    SEAT_TAKEN(HttpStatus.CONFLICT, "seat-taken", "이미 잡힌 좌석이 있다"),
    SEAT_NOT_IN_PERFORMANCE(HttpStatus.UNPROCESSABLE_CONTENT, "seat-not-in-performance", "이 회차의 좌석이 아니다"),
    OVER_LIMIT(HttpStatus.UNPROCESSABLE_CONTENT, "over-limit", "한 번에 잡을 수 있는 좌석 수를 넘었다"),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "reservation-not-found", "그런 예매가 없다"),
    DUPLICATE_HOLD(HttpStatus.CONFLICT, "duplicate-hold", "이 회차에 살아있는 선점이 이미 있다"),

    // 전이(`D3`). `hold-expired` 는 `invalid-transition` 의 특수형 — 화면이 「다시 고르세요」로 가른다(`D5`).
    INVALID_TRANSITION(HttpStatus.CONFLICT, "invalid-transition", "지금 상태에서 할 수 없다"),
    HOLD_EXPIRED(HttpStatus.CONFLICT, "hold-expired", "선점 시간이 지났다. 좌석을 다시 고른다"),
    CANCEL_WINDOW_CLOSED(HttpStatus.CONFLICT, "cancel-window-closed", "관람일 당일이라 취소할 수 없다"),

    // 좌석 읽기 모델(10a, `D20`). 변경 로그 밖을 물으면 410 — 화면이 그 코드로 전체를 다시 받는다.
    SEAT_CHANGES_EXPIRED(HttpStatus.GONE, "seat-changes-expired", "그 판 이후의 변경은 남아 있지 않다"),

    // 대기열(21, `D12`). 닫힌 회차는 **있었는데 끝난 것**이라 410 이고, 없는 회차(404)와 가른다 —
    // 화면이 앞에서는 줄을 걷고 뒤에서는 잘못된 링크를 말한다.
    QUEUE_CLOSED(HttpStatus.GONE, "queue-closed", "회차가 닫혀 대기열이 없다"),
    NOT_IN_QUEUE(HttpStatus.NOT_FOUND, "not-in-queue", "줄에 서 있지 않다"),

    // 관문(23). 429 는 「지금은 안 되지만 나중엔 된다」고, 줄을 안 선 사람은 403(「너는 안 된다」)이 아니다(`D12`).
    ADMISSION_REQUIRED(HttpStatus.TOO_MANY_REQUESTS, "admission-required", "대기열을 지나야 한다"),
    ADMISSION_MISMATCH(HttpStatus.FORBIDDEN, "admission-mismatch", "다른 계정·회차의 입장권이다"),
    // Redis 가 죽으면 관문을 **닫는다**. 열어 두면 대기열이 막으려던 폭발이 그대로 DB 로 간다(`D12` 「장애」).
    QUEUE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "queue-unavailable", "지금은 예매를 받을 수 없다"),

    // 멱등키(`D4`). 같은 키가 아직 처리 중이면 409, 같은 키에 다른 본문이면 422.
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "idempotency-in-progress", "같은 요청이 처리 중이다"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused", "같은 멱등키로 다른 요청이 왔다"),

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
