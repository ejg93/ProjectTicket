package com.projectticket.ticket.payment

import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 취소 입구(`D5` — `POST /api/reservations/{id}/cancel`, 세션·본인, 200). 상태를 바꾸는 것은 하위 경로에 `POST` 한다(`D5` 「메서드와 동작」).
 *
 * `reservation` 패키지가 아니라 여기 있는 이유는 의존 방향이다 — 취소는 환불(결제 패키지)을 부르고, 결제는 예매를 부른다. 반대로 두면 순환이다.
 */
@RestController
class RefundController(private val refundService: RefundService) {

    @PostMapping("/api/reservations/{reservationId}/cancel")
    fun cancel(@PathVariable reservationId: Long, @AuthenticationPrincipal user: TicketUser): RefundService.Result =
        refundService.cancel(user.id, reservationId)
}
