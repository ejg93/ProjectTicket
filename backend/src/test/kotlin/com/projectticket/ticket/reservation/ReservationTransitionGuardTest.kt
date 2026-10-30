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
import java.time.OffsetDateTime

/**
 * 예매 전이의 시각·결제 조건과 발권 조건을 트리거가 든다(`V28`, `state-machines.md` 「예매」). SQL 을 직접 던진다 — 서비스를 거치면
 * 조건부 UPDATE 가 먼저 막아서 트리거가 도는지를 못 본다(`SeatTransitionTriggerTest` 와 같은 이유).
 *
 * 막히는 경우는 **시험마다 하나씩 맨 끝에** 둔다 — 예외가 난 트랜잭션은 다음 문장을 못 돈다(`25P02`).
 */
class ReservationTransitionGuardTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var ticketService: TicketService

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
    fun a_reserved_reservation_from_the_fixture_gets_tickets() {
        val reservationId = hold("issue@test.local")
        fixture.reserve(reservationId)

        assertThat(ticketService.issue(reservationId, OffsetDateTime.now())).isEqualTo(1)
    }

    @Test
    fun a_lapsed_hold_cannot_start_paying() {
        val reservationId = hold("late-pay@test.local")
        jdbc.sql("update reservation set held_until = now() - interval '1 second' where reservation_id = :id").param("id", reservationId).update()

        assertRejected("선점 시각이 지났다") {
            jdbc.sql("update reservation set status = 'paying', paying_until = now() + interval '3 minutes' where reservation_id = :id")
                .param("id", reservationId)
                .update()
        }
    }

    @Test
    fun a_lapsed_payment_window_cannot_confirm() {
        val reservationId = hold("late-confirm@test.local")
        startPaying(reservationId, payingUntil = "now() - interval '1 second'")
        approve(reservationId)

        assertRejected("결제 시각이 지났다") { confirm(reservationId) }
    }

    @Test
    fun a_reservation_without_an_approved_payment_cannot_confirm() {
        val reservationId = hold("no-pay@test.local")
        startPaying(reservationId, payingUntil = "now() + interval '3 minutes'")

        assertRejected("승인 결제 없이") { confirm(reservationId) }
    }

    @Test
    fun a_held_reservation_cannot_get_a_ticket() {
        val reservationId = hold("early-ticket@test.local")
        val reservationSeatId = jdbc.sql("select reservation_seat_id from reservation_seat where reservation_id = :id")
            .param("id", reservationId)
            .query(Long::class.java)
            .single()

        assertRejected("확정 예매에만 발권한다") {
            jdbc.sql("insert into ticket (reservation_seat_id, ticket_number) values (:seat, '20261031-ABCDEF')")
                .param("seat", reservationSeatId)
                .update()
        }
    }

    private fun hold(email: String): Long =
        fixture.hold(accountId = fixture.account(email), performanceId = performanceId, section = "F1-A", seatNumber = 1)

    private fun startPaying(reservationId: Long, payingUntil: String) {
        jdbc.sql("update reservation set status = 'paying', paying_until = $payingUntil where reservation_id = :id")
            .param("id", reservationId)
            .update()
    }

    private fun approve(reservationId: Long) {
        jdbc.sql(
            """
            insert into payment (reservation_id, status, amount, approval_number, card_last4)
            select reservation_id, 'approved', total_amount, 'GUARD-' || reservation_id, '1234' from reservation where reservation_id = :id
            """,
        ).param("id", reservationId).update()
    }

    private fun confirm(reservationId: Long) {
        jdbc.sql("update reservation set status = 'reserved', reserved_at = now(), paying_until = null where reservation_id = :id")
            .param("id", reservationId)
            .update()
    }

    private fun assertRejected(message: String, statement: () -> Unit) {
        val thrown = catchThrowable(statement)
        assertThat(thrown).withFailMessage("트리거가 안 막았다 — $message").isNotNull()
        assertThat(thrown).hasStackTraceContaining(message)
    }
}
