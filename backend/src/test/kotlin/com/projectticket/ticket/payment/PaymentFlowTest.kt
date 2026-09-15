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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 결제 입구의 계약(`D5`)과 모의 PG 의 카드 표(ADR 0003) — 승인·거절·무응답·지연 넷이 실제로 갈리는가, 재전송이 같은 결제를 돌려주는가.
 *
 * 모의 PG 의 기억은 컨텍스트 하나에 하나라 멱등키를 테스트마다 새로 만든다.
 */
class PaymentFlowTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private lateinit var buyer: TicketUser
    private var performanceId: Long = 0
    private var reservationId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        buyer = principal("payer@test.local")
        val eventId = fixture.event(fixture.organizer())
        val hallId = fixture.hall()
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
        reservationId = seatHold.hold(buyer.id, SeatHoldService.Command(performanceId, fixture.performanceSeatIds(performanceId).take(2)))
    }

    @Test
    fun approved_card_confirms_the_reservation() {
        pay("4242 4242 4242 4242").andExpect {
            status { isCreated() }
            header { string("Location", "/api/reservations/$reservationId") }
            jsonPath("$.status") { value("approved") }
            jsonPath("$.amount") { value(308_000) }
            jsonPath("$.approval_number") { exists() }
            jsonPath("$.card_last4") { value("4242") }
            jsonPath("$.reservation_status") { value("reserved") }
        }

        mvc.get("/api/reservations/$reservationId") { with(user(buyer)) }
            .andExpect { jsonPath("$.status") { value("reserved") }; jsonPath("$.paying_until") { value(null) } }
    }

    @Test
    fun declined_card_is_a_result_not_an_error() {
        // 4xx 로 던지면 결제 행이 롤백돼 거절이 기록에 안 남는다(`D5`).
        pay("4242 4242 4242 0000").andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("declined") }
            jsonPath("$.decline_reason") { value("insufficient_funds") }
            jsonPath("$.reservation_status") { value("held") }
        }
    }

    @Test
    fun silent_gateway_is_recorded_as_failed() {
        pay("4242 4242 4242 0001").andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("failed") }
            jsonPath("$.decline_reason") { value("no_response") }
            jsonPath("$.reservation_status") { value("held") }
        }
    }

    @Test
    fun delayed_approval_is_recovered_by_inquiry() {
        // 응답은 잃었지만 승인은 났다 — 재시도가 아니라 상태 조회가 그것을 찾는다(`D4`). 재시도였으면 두 번 청구다.
        pay("4242 4242 4242 0002").andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("approved") }
            jsonPath("$.reservation_status") { value("reserved") }
        }
        assertThat(paymentCount()).isOne()
    }

    @Test
    fun same_key_replays_the_same_payment() {
        val key = UUID.randomUUID().toString()
        val first = pay("4242 4242 4242 4242", key).andReturn().response.contentAsString
        val second = pay("4242 4242 4242 4242", key).andExpect { status { isCreated() } }.andReturn().response.contentAsString

        assertThat(json.readTree(second)["payment_id"]).isEqualTo(json.readTree(first)["payment_id"])
        assertThat(paymentCount()).isOne()
    }

    @Test
    fun paying_an_already_reserved_reservation_is_an_invalid_transition() {
        pay("4242 4242 4242 4242").andExpect { status { isCreated() } }

        pay("4242 4242 4242 4242").andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:invalid-transition") }
            jsonPath("$.from") { value("reserved") }
            jsonPath("$.action") { value("pay") }
        }
    }

    @Test
    fun expired_hold_is_hold_expired() {
        jdbc.sql("update reservation set held_until = now() - interval '1 second' where reservation_id = :id").param("id", reservationId).update()

        pay("4242 4242 4242 4242").andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:hold-expired") }
        }
    }

    @Test
    fun someone_elses_reservation_is_not_found() {
        pay("4242 4242 4242 4242", principal = principal("stranger@test.local")).andExpect {
            status { isNotFound() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:reservation-not-found") }
        }
    }

    @Test
    fun malformed_card_number_is_validation_failed() {
        pay("12-34").andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:validation-failed") }
            jsonPath("$.errors[0].field") { value("card_number") }
        }
    }

    @Test
    fun missing_key_is_validation_failed() {
        pay("4242 4242 4242 4242", key = null).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("Idempotency-Key") }
        }
    }

    private fun pay(cardNumber: String, key: String? = UUID.randomUUID().toString(), principal: TicketUser = buyer): ResultActionsDsl =
        mvc.post("/api/reservations/$reservationId/payments") {
            with(user(principal))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("card_number" to cardNumber))
            if (key != null) header("Idempotency-Key", key)
        }

    private fun principal(email: String): TicketUser =
        TicketUser(fixture.account(email), email, AccountRole.AUDIENCE, passwordHash = null, active = true)

    private fun paymentCount(): Long =
        jdbc.sql("select count(*) from payment where reservation_id = :id").param("id", reservationId).query(Long::class.java).single()
}
