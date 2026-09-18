package com.projectticket.ticket.queue

import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 대기열 입구(`D5`·`D12`). **로그인해야 줄에 선다** — 줄의 member 가 계정이라 계정이 없으면 세울 자리가 없다.
 *
 * 이탈(`DELETE`)은 24 다. 여기는 201 이 아니라 200 이다 — 자원을 만드는 것이 아니라 줄에 서는 동작이라
 * 돌려줄 `Location` 이 없다(`D5`).
 */
@RestController
@RequestMapping("/api/queue")
class QueueController(private val queue: QueueService) {

    @PostMapping("/{performanceId}")
    fun enter(@PathVariable performanceId: Long, @AuthenticationPrincipal user: TicketUser): QueueService.Position =
        queue.enter(performanceId, user.id)

    /** 화면이 2초마다 부른다(ADR 0003). 토큰은 아직 안 준다 — 22 가 이 응답에 더한다 */
    @GetMapping("/{performanceId}")
    fun position(@PathVariable performanceId: Long, @AuthenticationPrincipal user: TicketUser): QueueService.Position =
        queue.position(performanceId, user.id)
}
