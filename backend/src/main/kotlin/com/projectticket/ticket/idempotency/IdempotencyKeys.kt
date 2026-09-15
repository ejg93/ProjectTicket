package com.projectticket.ticket.idempotency

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException

/** `Idempotency-Key` 헤더의 형식(`D4` 「멱등키」 — 1~255자, 클라이언트가 만든다). 선점·결제 두 입구가 같은 검사를 쓴다 */
object IdempotencyKeys {

    const val HEADER = "Idempotency-Key"

    /**
     * 없거나 길면 400 `validation-failed`(`D4`). 프레임워크에 맡기면(`required = true`) `malformed-request` 로 나가서 화면이 다른 가지를 탄다.
     * `errors[].field` 는 본문 필드가 아니라 헤더 이름이다 — 화면이 「어느 칸」이 아니라 「요청 자체」를 고쳐야 한다는 뜻이다.
     */
    fun require(header: String?): String =
        header?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
            ?: throw TicketException(
                ErrorCode.VALIDATION_FAILED,
                "$HEADER 헤더가 필요하다(1~${MAX_LENGTH}자)",
                mapOf("errors" to listOf(mapOf("field" to HEADER, "message" to "필수 헤더다. UUIDv4 를 권한다"))),
            )

    private const val MAX_LENGTH = 255
}
