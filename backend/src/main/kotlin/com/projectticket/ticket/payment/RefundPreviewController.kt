package com.projectticket.ticket.payment

import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * 환불 미리보기(`44a`, `D6`). 결제한 예매를 취소하기 전에 **얼마를 돌려받는지** 보여 준다. 결제 **전**에 구간표를 보여 주는 자리는 따로다(`42-1`).
 * 상태를 안 바꾸는 읽기라 GET 이다(`D5`). 계산은 취소와 같은 [RefundQuote] 다.
 */
@RestController
class RefundPreviewController(private val quote: RefundQuote) {

    @GetMapping("/api/reservations/{reservationId}/refund-preview")
    fun preview(@PathVariable reservationId: Long, @AuthenticationPrincipal user: TicketUser): RefundQuote.Preview =
        quote.preview(reservationId, user.id)
}
