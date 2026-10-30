package com.projectticket.ticket.event

import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.web.Paging
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 공연 목록·상세(`D5`). 로그인 없이 본다(`SecurityConfig.PUBLIC_GET_PATHS`) — 무엇을 하는지 보고 나서 로그인한다.
 *
 * **`ETag` 를 안 단다.** 좌석 현황([SeatController])은 초 단위로 바뀌는 것을 폴링하니 재검증이 값을 하지만,
 * 목록은 기획사가 회차를 열 때만 바뀌고 화면도 한 번 읽는다 — 버전을 만들 자리를 두는 값이 재검증으로 아끼는 것보다 크다.
 */
@RestController
@RequestMapping("/api/events")
class EventController(private val eventQuery: EventQuery, private val membership: OrganizerMembership) {

    /** `page`·`size`·`sort` 를 [Paging] 하나로 받는다(`D5` — 상한 보정이 그 타입에 있다) */
    @GetMapping
    fun events(paging: Paging): EventQuery.EventPage = eventQuery.list(paging)

    /**
     * **한 번도 공개된 회차가 없는 공연은 그 기획사 사람만 본다**(`D5` 「403 이냐 404 냐」, `I11-10`). 번호가 순번이라
     * 훑으면 미공개 공연의 제목·기획사·등급이 드러난다 — [PerformanceController] 가 `draft` 회차를 숨기는 것과 같은 판정이다.
     * 공개된 적이 있으면(`open`·`closed`·`cancelled`) 지금 살 것이 없어도 200 + 빈 목록이다 — 「없는 공연」과 다르다.
     */
    @GetMapping("/{eventId}")
    fun event(@PathVariable eventId: Long, @AuthenticationPrincipal user: TicketUser?): EventQuery.EventDetail {
        if (!eventQuery.hasPublicPerformance(eventId)) {
            // 비로그인·남의 기획사는 없는 공연과 한 이름이다 — 존재를 안 흘린다.
            if (user == null) throw TicketException(ErrorCode.EVENT_NOT_FOUND)
            membership.requireEvent(user.id, eventId, forWrite = false)
        }
        return eventQuery.detail(eventId)
    }
}
