package com.projectticket.ticket.reservation

import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * 선점은 회차 아래(`/api/performances/{id}/reservations`)에 있다 — 대기열 관문(23)이 회차 id 를 경로에서 읽는다(`D5`).
 * 만들어진 뒤에는 `/api/reservations/{id}` 로 평평하다.
 *
 * `X-Admission-Token` 은 여기서 안 읽는다. 23 이 이 컨트롤러 앞에 관문 필터를 세운다.
 */
@RestController
class ReservationController(
    private val reservationService: ReservationService,
    private val reservationQuery: ReservationQuery,
) {

    @PostMapping("/api/performances/{performanceId}/reservations")
    fun hold(
        @PathVariable performanceId: Long,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
        @Valid @RequestBody request: HoldRequest,
        @AuthenticationPrincipal user: TicketUser,
    ): ResponseEntity<ReservationQuery.Reservation> {
        // 없으면 400 `validation-failed`(`D4`). 프레임워크에 맡기면 `malformed-request` 로 나가서 화면이 다른 가지를 탄다.
        val key = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() && it.length <= 255 }
            ?: throw TicketException(
                ErrorCode.VALIDATION_FAILED,
                "$IDEMPOTENCY_KEY 헤더가 필요하다(1~255자)",
                mapOf("errors" to listOf(mapOf("field" to IDEMPOTENCY_KEY, "message" to "필수 헤더다. UUIDv4 를 권한다"))),
            )

        val reservation = reservationService.hold(user.id, key, SeatHoldService.Command(performanceId, request.seatIds))
        return ResponseEntity.created(URI.create("/api/reservations/${reservation.reservationId}")).body(reservation)
    }

    @GetMapping("/api/reservations/{reservationId}")
    fun get(@PathVariable reservationId: Long, @AuthenticationPrincipal user: TicketUser): ReservationQuery.Reservation =
        reservationQuery.get(reservationId, user.id)

    /** 상한(4석)은 여기가 아니라 서비스가 422 `over-limit` 으로 낸다 — `@Size` 로 걸면 400 `validation-failed` 가 되어 `D5` 표와 어긋난다 */
    data class HoldRequest(@field:NotEmpty val seatIds: List<Long>)

    companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }
}
