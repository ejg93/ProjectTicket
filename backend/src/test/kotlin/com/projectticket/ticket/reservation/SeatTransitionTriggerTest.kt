package com.projectticket.ticket.reservation

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 좌석 상태는 전이표 넷으로만 옮긴다(`V26`, `state-machines.md` 「좌석」). SQL 을 직접 던진다 — 서비스를 거치면 조건부 UPDATE 가 먼저 막아서
 * 트리거가 도는지를 못 본다(`SeatHandoverTest` 와 같은 이유).
 *
 * 막히는 경우는 **시험마다 하나씩 맨 끝에** 둔다 — 예외가 난 트랜잭션은 다음 문장을 못 돈다(`25P02`).
 */
class SeatTransitionTriggerTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private var performanceId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer())
        val hallId = fixture.hall()
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
    }

    @Test
    fun the_four_allowed_transitions_pass() {
        val reservationId = fixture.hold(accountId = fixture.account("seat@test.local"), performanceId = performanceId, section = "F1-A", seatNumber = 1)
        val seat = seatOf(reservationId)

        assertThat(reserve(seat)).describedAs("held→reserved 는 확정이다").isEqualTo(1)
        assertThat(free(seat)).describedAs("reserved→available 은 취소·환불이다").isEqualTo(1)
        assertThat(hold(seat, reservationId)).describedAs("available→held 는 선점이다").isEqualTo(1)
        assertThat(free(seat)).describedAs("held→available 은 만료·해제다").isEqualTo(1)
    }

    @Test
    fun an_available_seat_cannot_jump_to_reserved() {
        val reservationId = fixture.hold(accountId = fixture.account("jump@test.local"), performanceId = performanceId, section = "F1-A", seatNumber = 1)
        val free = fixture.performanceSeatIds(performanceId).first { it != seatOf(reservationId) }

        val thrown = catchThrowable {
            jdbc.sql("update performance_seat set status = 'reserved', reservation_id = :reservation where performance_seat_id = :seat")
                .param("reservation", reservationId)
                .param("seat", free)
                .update()
        }
        assertThat(thrown).withFailMessage("트리거가 안 막았다 — 선점 없이 좌석이 팔렸다").isNotNull()
        assertThat(thrown).hasStackTraceContaining("할 수 없는 좌석 상태 전이다")
    }

    @Test
    fun a_reserved_seat_cannot_go_back_to_held() {
        val reservationId = fixture.hold(accountId = fixture.account("back@test.local"), performanceId = performanceId, section = "F1-A", seatNumber = 1)
        val seat = seatOf(reservationId)
        reserve(seat)

        val thrown = catchThrowable {
            jdbc.sql("update performance_seat set status = 'held', held_until = now() + interval '5 minutes' where performance_seat_id = :seat")
                .param("seat", seat)
                .update()
        }
        assertThat(thrown).withFailMessage("트리거가 안 막았다 — 팔린 좌석이 선점으로 되돌아갔다").isNotNull()
        assertThat(thrown).hasStackTraceContaining("할 수 없는 좌석 상태 전이다")
    }

    private fun seatOf(reservationId: Long): Long =
        jdbc.sql("select performance_seat_id from performance_seat where reservation_id = :id")
            .param("id", reservationId)
            .query(Long::class.java)
            .single()

    private fun reserve(seat: Long): Int =
        jdbc.sql("update performance_seat set status = 'reserved', held_until = null where performance_seat_id = :seat")
            .param("seat", seat)
            .update()

    private fun free(seat: Long): Int =
        jdbc.sql("update performance_seat set status = 'available', held_until = null, reservation_id = null where performance_seat_id = :seat")
            .param("seat", seat)
            .update()

    private fun hold(seat: Long, reservationId: Long): Int =
        jdbc.sql(
            """
            update performance_seat
               set status = 'held', held_until = now() + interval '5 minutes', reservation_id = :reservation
             where performance_seat_id = :seat
            """,
        ).param("reservation", reservationId).param("seat", seat).update()
}
