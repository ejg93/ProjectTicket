package com.projectticket.ticket.payment

import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.idempotency.IdempotencyKeys
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 결제를 거는 입구(`D5` — `POST /api/reservations/{id}/payments`, 세션·본인). 멱등은 [PaymentService] 가 감싼다 — 순서를 아는 쪽이라서다.
 *
 * 카드번호는 여기까지만 온다. 서비스를 지나 모의 PG 까지 가고 우리 표에는 뒷 4자리만 닿는다(`D9`).
 * 형식 검사도 여기서 끝낸다 — 게이트웨이 쪽 검사는 그 뒤의 방벽이라 거기까지 가면 우리 코드가 틀린 것이다.
 */
@RestController
class PaymentController(private val paymentService: PaymentService) {

    /**
     * 거절도 201 이다 — 요청은 성공했고 결과가 거절인 것이라 본문의 `status` 가 말한다(`D5`).
     * `Location` 이 결제가 아니라 예매를 가리킨다. 결제 결과를 다시 보는 경로가 예매 상세뿐이다.
     */
    @PostMapping("/api/reservations/{reservationId}/payments")
    fun pay(
        @PathVariable reservationId: Long,
        @RequestHeader(name = IdempotencyKeys.HEADER, required = false) idempotencyKey: String?,
        @Valid @RequestBody request: PayRequest,
        @AuthenticationPrincipal user: TicketUser,
    ): ResponseEntity<PaymentService.Result> {
        val key = IdempotencyKeys.require(idempotencyKey)
        val result = paymentService.pay(user.id, key, PaymentService.Command(reservationId, request.cardNumber))
        return ResponseEntity.created(java.net.URI.create("/api/reservations/$reservationId")).body(result)
    }

    /** @param cardNumber 하이픈과 공백을 허용한다 — 사람이 화면에 입력한 모양 그대로 받는다. 12~19자리(ISO/IEC 7812) */
    data class PayRequest(@field:NotBlank @field:Pattern(regexp = "[0-9][0-9 -]{10,23}[0-9]") val cardNumber: String) {
        /** 카드번호를 로그·디버거에 안 찍는다(`D10`) */
        override fun toString(): String = "PayRequest[cardNumber=****]"
    }
}
