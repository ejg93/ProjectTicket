package com.projectticket.ticket.payment

import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 취소 입구(`D5` — `POST /api/reservations/{id}/cancel`, 세션·본인, 200). 상태를 바꾸는 것은 하위 경로에 `POST` 한다(`D5` 「메서드와 동작」).
 *
 * `reservation` 패키지가 아니라 여기 있는 이유는 의존 방향이다 — 취소는 환불(결제 패키지)을 부르고, 결제는 예매를 부른다. 반대로 두면 순환이다.
 */
@RestController
class RefundController(private val refundService: RefundService) {

    @PostMapping("/api/reservations/{reservationId}/cancel")
    fun cancel(
        @PathVariable reservationId: Long,
        @Valid @RequestBody request: CancelRequest,
        @AuthenticationPrincipal user: TicketUser,
    ): RefundService.Result =
        refundService.cancel(user.id, reservationId, request.refundAmount)

    /** 화면이 미리보기에서 본 환불액(`44a-1a`). 지금 계산과 다르면 409 `quote-changed` — 본 적 없는 금액으로 취소되지 않는다(`D6`) */
    data class CancelRequest(@field:NotNull val refundAmount: Int)
}
