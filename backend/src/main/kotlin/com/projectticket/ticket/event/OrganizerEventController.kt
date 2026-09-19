package com.projectticket.ticket.event

import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.OffsetDateTime

/**
 * 기획사 입구(11). **역할은 여기서 안 본다** — `/api/organizer/…` 에 경로 규칙이 걸려 있어(`SecurityConfig`) 이 메서드에 닿았다는 것이 곧 `ROLE_ORGANIZER` 다.
 * 그 403 은 `organizer-forbidden` 으로 나간다(`ProblemAccessDeniedHandler`).
 *
 * **소속은 서비스가 본다.** 남의 기획사·공연·회차는 404 다(`D5` 「403 이냐 404 냐」) — 403 이면 다른 기획사의 미공개 공연 존재가 샌다.
 *
 * 회차 취소는 여기 없다. `PerformanceCancelService` 가 환불을 만져 `payment` 패키지에 있고, `event → payment` 는 의존 방향을 거스른다(`D14`) —
 * 그 입구는 `payment/OrganizerCancelController` 다.
 */
@RestController
@RequestMapping("/api/organizer")
class OrganizerEventController(
    private val eventService: OrganizerEventService,
    private val openService: PerformanceOpenService,
    private val membership: OrganizerMembership,
) {

    @PostMapping("/events")
    fun createEvent(@Valid @RequestBody request: CreateEventRequest, @AuthenticationPrincipal user: TicketUser): ResponseEntity<CreatedEvent> {
        val eventId = eventService.createEvent(
            user.id,
            OrganizerEventService.EventCommand(
                organizerId = request.organizerId,
                title = request.title,
                grades = request.grades.map { OrganizerEventService.GradeCommand(it.code, it.price, it.sections) },
            ),
        )
        return ResponseEntity.created(URI.create("/api/events/$eventId")).body(CreatedEvent(eventId))
    }

    @PostMapping("/events/{eventId}/performances")
    fun createPerformance(
        @PathVariable eventId: Long,
        @Valid @RequestBody request: CreatePerformanceRequest,
        @AuthenticationPrincipal user: TicketUser,
    ): ResponseEntity<CreatedPerformance> {
        val performanceId = eventService.createPerformance(
            user.id,
            eventId,
            OrganizerEventService.PerformanceCommand(request.hallId, request.startsAt, request.salesOpenAt, request.salesCloseAt),
        )
        return ResponseEntity.created(URI.create("/api/performances/$performanceId")).body(CreatedPerformance(performanceId))
    }

    /** 좌석 복제가 여기서 일어난다(9). 200 인 이유는 자원을 만드는 것이 아니라 **상태를 옮기는 것**이라서다(`D5` 「메서드와 동작」) */
    @PostMapping("/performances/{performanceId}/open")
    fun open(@PathVariable performanceId: Long, @AuthenticationPrincipal user: TicketUser): OpenedPerformance {
        membership.requirePerformance(user.id, performanceId)
        return OpenedPerformance(performanceId, openService.open(performanceId, user.id))
    }

    /** 등급은 공연 단위다(ADR 0003). 구역 형식은 `seat_section_format_check`(`V4`)가 든다 — 여기 정규식은 그 사본이 아니라 입구의 1차 거름이다 */
    data class GradeRequest(
        @field:NotBlank @field:Pattern(regexp = "^[A-Z][A-Z0-9]{0,9}$") val code: String,
        @field:NotNull @field:PositiveOrZero val price: Int,
        @field:NotEmpty @field:SectionCodes val sections: List<String>,
    )

    data class CreateEventRequest(
        @field:NotNull val organizerId: Long,
        @field:NotBlank @field:Size(max = 200) val title: String,
        // 등급 없이 만들면 회차를 못 연다. 빠뜨린 것과 아직 안 넣은 것이 같아 보이지 않게 여기서 요구한다.
        @field:NotEmpty @field:Valid val grades: List<GradeRequest>,
    )

    data class CreatePerformanceRequest(
        @field:NotNull val hallId: Long,
        // 시각은 오프셋 필수다(`D7`) — `OffsetDateTime` 으로 받으면 없는 요청이 400 이다.
        @field:NotNull val startsAt: OffsetDateTime,
        @field:NotNull val salesOpenAt: OffsetDateTime,
        val salesCloseAt: OffsetDateTime?,
    )

    data class CreatedEvent(val eventId: Long)

    data class CreatedPerformance(val performanceId: Long)

    data class OpenedPerformance(val performanceId: Long, val seatCount: Int)
}
