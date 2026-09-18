package com.projectticket.ticket.payment

import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.event.OrganizerMembership
import com.projectticket.ticket.queue.QueueService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 회차 취소 입구(11, 서비스는 17a 가 세웠다).
 *
 * **`event` 가 아니라 여기 있다.** [PerformanceCancelService] 가 환불을 만져 `payment` 패키지고, `event → payment` 는 의존 방향을 거스른다(`D14`).
 * 경로는 `/api/organizer/…` 라 역할은 경로 규칙이 보고(`SecurityConfig`) 소속은 [OrganizerMembership] 이 본다 — 남의 회차는 404 다.
 */
@RestController
class OrganizerCancelController(
    private val cancelService: PerformanceCancelService,
    private val membership: OrganizerMembership,
    private val queue: QueueService,
) {

    /** 200 이다 — 자원을 만드는 것이 아니라 상태를 옮긴다(`D5`). 취소된 예매 수를 돌려준다 */
    @PostMapping("/api/organizer/performances/{performanceId}/cancel")
    fun cancel(@PathVariable performanceId: Long, @AuthenticationPrincipal user: TicketUser): Cancelled {
        membership.requirePerformance(user.id, performanceId)
        val cancelled = cancelService.cancel(performanceId, user.id)
        // 취소가 커밋된 뒤에 줄을 걷는다(`D12`). 취소가 실패하면 줄은 그대로 있어야 한다 — 회차는 아직 열려 있다.
        queue.drop(performanceId)
        return Cancelled(performanceId, cancelled)
    }

    data class Cancelled(val performanceId: Long, val cancelledReservationCount: Int)
}
