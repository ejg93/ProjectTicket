package com.projectticket.ticket.event

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 회차 하나(`D5`, `40c`). 로그인 없이 본다(`SecurityConfig.PUBLIC_PATHS`) — 좌석도처럼 보고 나서 로그인한다.
 *
 * [EventController] 와 가른 이유는 접두다 — 그쪽은 `/api/events` 라 이 주소를 못 받는다.
 */
@RestController
@RequestMapping("/api/performances")
class PerformanceController(private val performanceQuery: PerformanceQuery) {

    @GetMapping("/{performanceId}")
    fun performance(@PathVariable performanceId: Long): PerformanceQuery.PerformanceDetail = performanceQuery.detail(performanceId)
}
