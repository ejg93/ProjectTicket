package com.projectticket.ticket.account

import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.reservation.MyReservationQuery
import com.projectticket.ticket.web.Paging
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 내 예매 목록(`44a`). 세션의 계정만 본다 — 경로에 계정 번호가 없어서 남의 목록을 물을 길이 없다(`D5` 「`/me`」).
 * `page`·`size` 는 [Paging] 하나로 받는다(상한 보정이 그 타입에 있다). 정렬은 고정이라 `sort` 를 안 쓴다.
 */
@RestController
class MeReservationController(private val query: MyReservationQuery) {

    @GetMapping("/api/me/reservations")
    fun reservations(@AuthenticationPrincipal user: TicketUser, paging: Paging): MyReservationQuery.MyReservationPage =
        query.list(user.id, paging)
}
