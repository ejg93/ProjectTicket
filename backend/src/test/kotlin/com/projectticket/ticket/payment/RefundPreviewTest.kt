package com.projectticket.ticket.payment

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.reservation.SeatHoldService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

/**
 * 환불 미리보기가 **실제 취소와 같은 액수**를 말하는가(`44a`, `D6`).
 *
 * 두 벌로 계산하면 화면이 보여 준 액수와 환불 행이 갈린다 — 사용자는 본 금액을 약속으로 읽는다. 그래서 계산을 [RefundQuote] 하나로 모았고,
 * 여기서는 **미리보기를 받은 직후 같은 예매를 취소해** 환불 행의 액수가 같은지 본다.
 */
class RefundPreviewTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var payments: PaymentTransitionService
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private lateinit var buyer: TicketUser
    private var eventId: Long = 0
    private var hallId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        buyer = principal("preview@test.local")
        eventId = fixture.event(fixture.organizer())
        hallId = fixture.hall()
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
    }

    @Test
    fun the_preview_is_what_the_cancel_refunds() {
        val reservationId = reserved(startsInDays = 8)

        val preview = json.readTree(
            mvc.get("/api/reservations/$reservationId/refund-preview") { with(user(buyer)) }
                .andExpect { status { isOk() } }.andReturn().response.contentAsString,
        )
        assertThat(preview["cancellable"].asBoolean()).isTrue()
        assertThat(preview["payment_amount"].asInt()).isEqualTo(308_000)

        mvc.post("/api/reservations/$reservationId/cancel") {
            with(user(buyer)); with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"refund_amount":${preview["refund_amount"].asInt()}}"""
        }.andExpect { status { isOk() } }

        val refund = jdbc.sql(
            """
            select r.days_before, r.fee_amount, r.refund_amount
              from refund r join payment p on p.payment_id = r.payment_id
             where p.reservation_id = :id
            """,
        ).param("id", reservationId).query { rs, _ -> Triple(rs.getInt(1), rs.getInt(2), rs.getInt(3)) }.single()

        // 화면이 보여 준 셋이 환불 행과 같다 — 같은 함수라서다.
        assertThat(Triple(preview["days_before"].asInt(), preview["fee_amount"].asInt(), preview["refund_amount"].asInt())).isEqualTo(refund)
    }

    @Test
    fun a_held_reservation_is_not_cancellable_and_says_why() {
        val reservationId = hold(startsInDays = 8)

        // 결제 전이라 돌려줄 돈이 없다. 액수는 0 이 아니라 없다(null) — 「환불 0원」과 안 갈리면 안 된다.
        mvc.get("/api/reservations/$reservationId/refund-preview") { with(user(buyer)) }.andExpect {
            status { isOk() }
            jsonPath("$.cancellable") { value(false) }
            jsonPath("$.reason") { value("invalid-transition") }
            jsonPath("$.refund_amount") { value(null as Any?) }
        }
    }

    @Test
    fun the_day_of_the_show_is_not_cancellable() {
        val reservationId = reserved(startsInDays = 0)

        mvc.get("/api/reservations/$reservationId/refund-preview") { with(user(buyer)) }.andExpect {
            status { isOk() }
            jsonPath("$.cancellable") { value(false) }
            jsonPath("$.reason") { value("cancel-window-closed") }
            jsonPath("$.days_before") { value(0) }
        }
    }

    @Test
    fun someone_elses_reservation_is_not_found() {
        val reservationId = reserved(startsInDays = 8)
        val stranger = principal("stranger@test.local")

        mvc.get("/api/reservations/$reservationId/refund-preview") { with(user(stranger)) }.andExpect {
            status { isNotFound() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:reservation-not-found") }
        }
    }

    private fun hold(startsInDays: Long): Long {
        val performanceId = fixture.performance(eventId, hallId, startsInDays).also { openService.open(it, actorAccountId = null) }
        return seatHold.hold(buyer.id, SeatHoldService.Command(performanceId, fixture.performanceSeatIds(performanceId)))
    }

    /** 2석 308,000원을 결제까지 마친 예매 */
    private fun reserved(startsInDays: Long): Long {
        val reservationId = hold(startsInDays)
        val paying = payments.startPaying(buyer.id, reservationId)
        payments.settle(buyer.id, paying, MockPaymentGateway.Result("M-${System.nanoTime()}", "4242", null))
        return reservationId
    }

    private fun principal(email: String): TicketUser =
        TicketUser(fixture.account(email), email, AccountRole.AUDIENCE, passwordHash = null, active = true)
}
