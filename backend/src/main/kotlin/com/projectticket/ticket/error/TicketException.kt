package com.projectticket.ticket.error

/**
 * 서비스가 던지는 유일한 업무 예외.
 *
 * 서비스는 무엇이 잘못됐는지만 말하고 그것이 몇 번인지는 안 정한다(`D14`). 상태 코드를 서비스가 정하면
 * 웹을 아는 서비스가 되고, 배치나 다른 입구에서 재사용할 때 어색해진다. 번역은 [ApiExceptionHandler] 한 곳에서 한다.
 *
 * @param detail 이 자리에서만 쓰는 설명. 그대로 응답에 나간다 — 식별자는 괜찮고, 이메일·SQL·스택은 안 된다(`D9`·`D10`).
 * @param properties 오류 본문의 추가 필드(`D5` 「type 목록」의 「추가 필드」 열). `taken_seat_ids` 처럼 화면이 기계값으로 읽는 것이다.
 *   키는 응답 필드 이름 그대로 snake_case 다 — `ProblemDetail` 의 속성은 Jackson 의 이름 전략을 안 탄다.
 */
class TicketException(
    val code: ErrorCode,
    detail: String? = null,
    val properties: Map<String, Any?> = emptyMap(),
) : RuntimeException(detail ?: code.title)
