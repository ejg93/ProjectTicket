package com.projectticket.ticket.event

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.payment.MockPaymentGateway
import com.projectticket.ticket.payment.PaymentTransitionService
import com.projectticket.ticket.reservation.SeatHoldService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper

/**
 * 기획사가 자기 공연을 읽는가(`45a`, `D5`). **남의 공연은 404** · **오픈 전 회차도 보인다**(공개 목록과 다르다) ·
 * **팔린 수는 `reserved` 만** — `held` 는 선점일 뿐이다.
 */
class OrganizerEventReadTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var payments: PaymentTransitionService

    private lateinit var fixture: EventFixture
    private lateinit var mine: TicketUser
    private lateinit var stranger: TicketUser
    private var eventId: Long = 0
    private var hallId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        val myOrganizer = fixture.organizer("read-mine")
        mine = organizerUser("read-mine@test.local", myOrganizer)
        stranger = organizerUser("read-other@test.local", fixture.organizer("read-other"))
        hallId = fixture.hall()
        fixture.seats(hallId, "F1-A", 3)
        eventId = fixture.event(myOrganizer, title = "기획사 읽기 공연")
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 10_000))
    }

    @Test
    fun my_events_include_ones_without_an_open_performance() {
        fixture.performance(eventId, hallId)

        val body = json.readTree(mvc.get("/api/organizer/events") { with(user(mine)) }.andReturn().response.contentAsString)

        // 공개 목록(`/api/events`)은 판매 중 회차가 있는 공연만 낸다. 기획사는 오픈 전 것도 봐야 한다.
        assertThat(body["total"].asInt()).isEqualTo(1)
        assertThat(body["items"][0]["title"].asText()).isEqualTo("기획사 읽기 공연")
        assertThat(body["items"][0]["performance_count"].asInt()).isEqualTo(1)
        mvc.get("/api/organizer/events") { with(user(stranger)) }.andExpect { jsonPath("$.total") { value(0) } }
    }

    @Test
    fun the_detail_counts_only_reserved_seats_as_sold() {
        val draft = fixture.performance(eventId, hallId, startsInDays = 5)
        val open = fixture.performance(eventId, hallId, startsInDays = 6).also { openService.open(it, actorAccountId = null) }
        val seats = fixture.performanceSeatIds(open)
        val buyer = fixture.account("read-buyer@test.local")
        // 하나는 결제까지(reserved), 하나는 선점만(held)
        val paid = seatHold.hold(buyer, SeatHoldService.Command(open, listOf(seats[0])))
        payments.settle(buyer, payments.startPaying(buyer, paid), MockPaymentGateway.Result("M-${System.nanoTime()}", "4242", null))
        seatHold.hold(fixture.account("read-holder@test.local"), SeatHoldService.Command(open, listOf(seats[1])))

        val body = json.readTree(mvc.get("/api/organizer/events/$eventId") { with(user(mine)) }.andReturn().response.contentAsString)
        val byId = (0 until body["performances"].size()).associate { i ->
            body["performances"][i].let { it["performance_id"].asLong() to it }
        }

        assertThat(byId.keys).containsExactlyInAnyOrder(draft, open)
        assertThat(byId.getValue(draft)["status"].asText()).isEqualTo("draft")
        assertThat(byId.getValue(open)["seat_count"].asInt()).isEqualTo(3)
        assertThat(byId.getValue(open)["sold_count"].asInt()).isEqualTo(1)
    }

    @Test
    fun someone_elses_event_is_not_found() {
        mvc.get("/api/organizer/events/$eventId") { with(user(stranger)) }.andExpect {
            status { isNotFound() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:event-not-found") }
        }
    }

    /** 기획사 역할 계정 + 그 기획사 소속. 둘 다 있어야 입구를 지난다 */
    private fun organizerUser(email: String, organizerId: Long): TicketUser {
        val accountId = jdbc.sql(
            "insert into account (email, password_hash, display_name, role) values (:email, 'x', '담당자', 'organizer') returning account_id",
        ).param("email", email).query(Long::class.java).single()
        jdbc.sql("insert into organizer_member (organizer_id, account_id) values (:organizer, :account)")
            .param("organizer", organizerId).param("account", accountId).update()
        return TicketUser(accountId, email, AccountRole.ORGANIZER, null, true)
    }
}
