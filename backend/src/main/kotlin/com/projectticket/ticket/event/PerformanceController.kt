package com.projectticket.ticket.event

import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 회차 하나(`D5`, `40c`). 로그인 없이 본다(`SecurityConfig.PUBLIC_GET_PATHS`) — 좌석도처럼 보고 나서 로그인한다.
 *
 * **`draft` 는 그 기획사 사람만 본다.** 회차 등록 201 의 `Location` 이 이 주소라 등록한 쪽에는 답해야 하고(RFC 9110),
 * 남에게는 **없는 회차**다 — 번호가 순번이라 훑으면 미공개 공연이 드러난다(`D5` 「기획사의 공연·회차 404」, 마무리 13차 독립 리뷰).
 * 좌석도(`SeatQuery`)가 `draft` 를 404 로 숨기는 것과 같은 판정이다.
 *
 * [EventController] 와 가른 이유는 접두다 — 그쪽은 `/api/events` 라 이 주소를 못 받는다.
 */
@RestController
@RequestMapping("/api/performances")
class PerformanceController(private val performanceQuery: PerformanceQuery, private val membership: OrganizerMembership) {

    @GetMapping("/{performanceId}")
    fun performance(@PathVariable performanceId: Long, @AuthenticationPrincipal user: TicketUser?): PerformanceQuery.PerformanceDetail {
        val detail = performanceQuery.detail(performanceId)
        if (detail.status == DRAFT) {
            // 비로그인·남의 기획사는 없는 회차와 한 이름이다 — 존재를 안 흘린다.
            if (user == null) throw TicketException(ErrorCode.PERFORMANCE_NOT_FOUND)
            membership.requirePerformance(user.id, performanceId)
        }
        return detail
    }

    private companion object {
        const val DRAFT = "draft"
    }
}
