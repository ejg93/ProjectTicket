package com.projectticket.ticket.settlement

import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 기획사 정산 조회(`45c`). 경로가 기획사 접두(`/api/organizer/`) 아래라 역할은 경로 규칙이 보고, 소속은 [SettlementQuery] 의 조인이 본다.
 * 회차 하나를 묻는다 — 화면은 공연 화면 안에서 회차마다 한 칸을 그린다(따로 페이지를 안 만든다, 설계).
 */
@RestController
class OrganizerSettlementController(private val query: SettlementQuery) {

    @GetMapping("/api/organizer/settlements")
    fun settlement(@RequestParam performanceId: Long, @AuthenticationPrincipal user: TicketUser): SettlementQuery.Settlement =
        query.forPerformance(user.id, performanceId)
}
