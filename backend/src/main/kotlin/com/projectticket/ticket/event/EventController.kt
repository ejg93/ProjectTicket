package com.projectticket.ticket.event

import com.projectticket.ticket.web.Paging
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 공연 목록·상세(`D5`). 로그인 없이 본다(`SecurityConfig.PUBLIC_PATHS`) — 무엇을 하는지 보고 나서 로그인한다.
 *
 * **`ETag` 를 안 단다.** 좌석 현황([SeatController])은 초 단위로 바뀌는 것을 폴링하니 재검증이 값을 하지만,
 * 목록은 기획사가 회차를 열 때만 바뀌고 화면도 한 번 읽는다 — 버전을 만들 자리를 두는 값이 재검증으로 아끼는 것보다 크다.
 */
@RestController
@RequestMapping("/api/events")
class EventController(private val eventQuery: EventQuery) {

    /** `page`·`size`·`sort` 를 [Paging] 하나로 받는다(`D5` — 상한 보정이 그 타입에 있다) */
    @GetMapping
    fun events(paging: Paging): EventQuery.EventPage = eventQuery.list(paging)

    @GetMapping("/{eventId}")
    fun event(@PathVariable eventId: Long): EventQuery.EventDetail = eventQuery.detail(eventId)
}
