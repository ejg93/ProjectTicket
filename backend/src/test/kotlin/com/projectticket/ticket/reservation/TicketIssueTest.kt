package com.projectticket.ticket.reservation

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.payment.MockPaymentGateway
import com.projectticket.ticket.payment.PaymentTransitionService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 확정이 좌석마다 티켓 하나를 만드는가, 번호가 규칙(identifier-rules)대로인가, 조회가 본인에게만 열리는가.
 */
class TicketIssueTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var payments: PaymentTransitionService
    @Autowired lateinit var tickets: TicketService
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private lateinit var buyer: TicketUser
    private var performanceId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        buyer = principal("ticket@test.local")
        val eventId = fixture.event(fixture.organizer())
        val hallId = fixture.hall()
        fixture.seats(hallId, "F1-A", 3)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId).also { openService.open(it, actorAccountId = null) }
    }

    @Test
    fun confirmation_issues_one_ticket_per_seat() {
        val reservationId = reserved(seats = 2)

        val numbers = ticketNumbers(reservationId)
        assertThat(numbers).hasSize(2).doesNotHaveDuplicates()
        // 날짜 8자리 + 하이픈 + 혼동 글자를 뺀 난수 6자. `0`·`1`·`O`·`I` 가 없다.
        assertThat(numbers).allMatch { it.matches(Regex("^[0-9]{8}-[2-9A-HJ-NP-Z]{6}$")) }
    }

    @Test
    fun issuing_twice_does_not_duplicate() {
        val reservationId = reserved(seats = 2)

        // 확정이 두 번 와도(재전송) 표가 네 장 되지 않는다.
        assertThat(tickets.issue(reservationId, OffsetDateTime.now(ZoneOffset.UTC))).isZero()
        assertThat(ticketNumbers(reservationId)).hasSize(2)
    }

    @Test
    fun tickets_are_listed_for_the_owner_only() {
        val reservationId = reserved(seats = 2)

        mvc.get("/api/reservations/$reservationId/tickets") { with(user(buyer)) }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
                jsonPath("$[0].ticket_number") { exists() }
                jsonPath("$[0].section") { value("F1-A") }
                jsonPath("$[0].row_label") { value("A") }
                jsonPath("$[0].seat_number") { value(1) }
                jsonPath("$[0].price") { value(154_000) }
                jsonPath("$[0].issued_at") { exists() }
            }

        // 남의 예매는 없는 것이다 — 번호를 훑으면 판매량 지도가 그려진다(`D5` 「403 이냐 404 냐」).
        mvc.get("/api/reservations/$reservationId/tickets") { with(user(principal("stranger@test.local"))) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun unpaid_reservation_has_no_tickets_yet() {
        val held = hold(seats = 1)

        mvc.get("/api/reservations/$held/tickets") { with(user(buyer)) }
            .andExpect { status { isOk() }; content { json("[]") } }
    }

    @Test
    fun number_format_is_enforced_by_the_schema() {
        val reservationId = hold(seats = 1)
        val seatRecord = jdbc.sql("select reservation_seat_id from reservation_seat where reservation_id = :id").param("id", reservationId).query(Long::class.java).single()

        // 규칙을 적은 문서가 그 규칙을 어긴 예시를 든 적이 있다(identifier-rules). 제약이 잡는다 — `1` 과 `O` 가 들어 있다.
        assertThatThrownBy {
            jdbc.sql("insert into ticket (reservation_seat_id, ticket_number) values (:seat, '20260915-1O2345')").param("seat", seatRecord).update()
        }.hasStackTraceContaining("ticket_ticket_number_format_check")
    }

    @Test
    fun ticket_cannot_be_updated() {
        val reservationId = reserved(seats = 1)
        assertThatThrownBy {
            jdbc.sql("update ticket set ticket_number = '20260915-ABCDEF' where reservation_seat_id in (select reservation_seat_id from reservation_seat where reservation_id = :id)")
                .param("id", reservationId).update()
        }.hasStackTraceContaining("티켓은 고칠 수 없다")
    }

    private fun hold(seats: Int): Long =
        seatHold.hold(buyer.id, SeatHoldService.Command(performanceId, fixture.performanceSeatIds(performanceId).take(seats)))

    private fun reserved(seats: Int): Long {
        val reservationId = hold(seats)
        payments.settle(buyer.id, payments.startPaying(buyer.id, reservationId), MockPaymentGateway.Result("M-${System.nanoTime()}", "4242", null))
        return reservationId
    }

    private fun ticketNumbers(reservationId: Long): List<String> =
        jdbc.sql(
            "select t.ticket_number from ticket t join reservation_seat rs on rs.reservation_seat_id = t.reservation_seat_id where rs.reservation_id = :id",
        ).param("id", reservationId).query(String::class.java).list().filterNotNull()

    private fun principal(email: String): TicketUser =
        TicketUser(fixture.account(email), email, AccountRole.AUDIENCE, passwordHash = null, active = true)
}
