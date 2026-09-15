package com.projectticket.ticket.payment

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.reservation.SeatHoldService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import java.math.BigDecimal

/**
 * 관객 취소가 `D6` 대로 도는가 — 구간표 행에서 율을 고르고, 박제하고, 좌석을 돌려주고, 당일은 막는다.
 *
 * 관람일은 `now() + N일` 로 만든다. 시각이 같아서 KST 달력일 차가 정확히 N 이다 — 자정 경계 자체는 `RefundPolicyTest` 가 잰다.
 */
class ReservationCancelTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
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
        buyer = principal("canceller@test.local")
        eventId = fixture.event(fixture.organizer())
        hallId = fixture.hall()
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
    }

    @Test
    fun cancel_refunds_by_tier_and_frees_the_seats() {
        val reservationId = reserved(startsInDays = 8)

        cancel(reservationId).andExpect {
            status { isOk() }
            jsonPath("$.days_before") { value(8) }
            jsonPath("$.tier_rate") { value(0.10) }
            jsonPath("$.fee_amount") { value(30_800) }
            jsonPath("$.refund_amount") { value(277_200) }
            jsonPath("$.status") { value("done") }
        }

        assertThat(reservation(reservationId)).isEqualTo("cancelled" to true)
        assertThat(seatStatuses(reservationId)).containsOnly("available")
        assertThat(refundRow(reservationId)).isEqualTo(RefundRow("audience", 8, BigDecimal("0.10"), 30_800, 277_200, "done", true))
        assertThat(auditCount("reservation.cancelled", reservationId)).isOne()
    }

    @Test
    fun ten_days_before_is_free() {
        cancel(reserved(startsInDays = 10)).andExpect {
            jsonPath("$.tier_rate") { value(0.0) }
            jsonPath("$.fee_amount") { value(0) }
            jsonPath("$.refund_amount") { value(308_000) }
        }
    }

    @Test
    fun tiers_follow_the_table() {
        cancel(reserved(startsInDays = 5)).andExpect { jsonPath("$.tier_rate") { value(0.20) } }
    }

    @Test
    fun one_day_before_is_thirty_percent() {
        cancel(reserved(startsInDays = 1)).andExpect { jsonPath("$.tier_rate") { value(0.30) }; jsonPath("$.fee_amount") { value(92_400) } }
    }

    @Test
    fun same_day_cancel_is_closed_and_nothing_moves() {
        val reservationId = reserved(startsInDays = 0)

        cancel(reservationId).andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:cancel-window-closed") }
            jsonPath("$.starts_at") { exists() }
        }
        // 「아무것도 안 움직였다」는 여기서 못 본다 — 조건부 UPDATE 가 돈 뒤 예외가 rollback-only 표시만 남긴다(`stack.md`). `RefundInvariantTest` 가 커밋 레인에서 잰다.
    }

    @Test
    fun held_reservation_cannot_be_cancelled_here() {
        val held = hold(startsInDays = 8)

        // 결제 전 취소는 그냥 두면 만료된다 — 돈이 없으니 환불도 없다(`D6`).
        cancel(held).andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:invalid-transition") }
            jsonPath("$.from") { value("held") }
            jsonPath("$.action") { value("cancel") }
        }
    }

    @Test
    fun second_cancel_is_an_invalid_transition() {
        val reservationId = reserved(startsInDays = 8)
        cancel(reservationId).andExpect { status { isOk() } }

        cancel(reservationId).andExpect {
            status { isConflict() }
            jsonPath("$.from") { value("cancelled") }
        }
    }

    @Test
    fun someone_elses_reservation_is_not_found() {
        cancel(reserved(startsInDays = 8), principal("stranger@test.local")).andExpect { status { isNotFound() } }
    }

    @Test
    fun one_payment_gets_one_refund() {
        val reservationId = reserved(startsInDays = 8)
        cancel(reservationId).andExpect { status { isOk() } }
        val paymentId = jdbc.sql("select payment_id from payment where reservation_id = :id").param("id", reservationId).query(Long::class.java).single()

        // 결제당 환불은 하나다(`D6`). 둘이면 같은 돈이 두 번 나간다 — `refund_payment_id_key`.
        assertThatThrownBy {
            jdbc.sql(
                """
                insert into refund (payment_id, reason, days_before, tier_rate, fee_amount, refund_amount)
                values (:payment, 'audience', 8, 0.10, 30800, 277200)
                """,
            ).param("payment", paymentId).update()
        }.hasStackTraceContaining("refund_payment_id_key")
    }

    @Test
    fun non_audience_reason_must_be_full_refund() {
        val reservationId = reserved(startsInDays = 8)
        val paymentId = jdbc.sql("select payment_id from payment where reservation_id = :id").param("id", reservationId).query(Long::class.java).single()

        // 회차 취소·승인 지연은 관객 잘못이 아니다(`D6` 「사유별」). 율이 붙은 채로 들어오면 여기서 막힌다.
        assertThatThrownBy {
            jdbc.sql(
                """
                insert into refund (payment_id, reason, days_before, tier_rate, fee_amount, refund_amount)
                values (:payment, 'performance_cancelled', 8, 0.10, 30800, 277200)
                """,
            ).param("payment", paymentId).update()
        }.hasStackTraceContaining("refund_full_for_non_audience_check")
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

    private fun cancel(reservationId: Long, principal: TicketUser = buyer): ResultActionsDsl =
        mvc.post("/api/reservations/$reservationId/cancel") { with(user(principal)); with(csrf()) }

    private fun principal(email: String): TicketUser =
        TicketUser(fixture.account(email), email, AccountRole.AUDIENCE, passwordHash = null, active = true)

    private fun reservation(reservationId: Long): Pair<String, Boolean> =
        jdbc.sql("select status, cancelled_at is not null as cancelled from reservation where reservation_id = :id")
            .param("id", reservationId).query { rs, _ -> rs.getString("status") to rs.getBoolean("cancelled") }.single()

    private fun seatStatuses(reservationId: Long): List<String> =
        jdbc.sql(
            """
            select ps.status from performance_seat ps
             where ps.performance_id = (select performance_id from reservation where reservation_id = :id)
            """,
        ).param("id", reservationId).query(String::class.java).list().filterNotNull()

    data class RefundRow(val reason: String, val daysBefore: Int, val tierRate: BigDecimal, val fee: Int, val refund: Int, val status: String, val numbered: Boolean)

    private fun refundRow(reservationId: Long): RefundRow =
        jdbc.sql(
            """
            select r.reason, r.days_before, r.tier_rate, r.fee_amount as fee, r.refund_amount as refund, r.status, r.refund_number is not null as numbered
              from refund r join payment p on p.payment_id = r.payment_id
             where p.reservation_id = :id
            """,
        ).param("id", reservationId).query(RefundRow::class.java).single()

    private fun auditCount(eventType: String, targetId: Long): Long =
        jdbc.sql("select count(*) from audit_log where event_type = :type and target_id = :id")
            .param("type", eventType).param("id", targetId).query(Long::class.java).single()
}
