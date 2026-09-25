package com.projectticket.ticket.reservation

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
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
 * 내 예매 목록(`44a`). **남의 예매가 안 보이는가**가 먼저고, 그다음이 목록 규약(`D5` — `items`·`page`·`size`·`total`)과 순서다.
 */
class MyReservationsTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private lateinit var me: TicketUser
    private lateinit var other: TicketUser
    private var eventId: Long = 0
    private var hallId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        me = principal("mine@test.local")
        other = principal("theirs@test.local")
        eventId = fixture.event(fixture.organizer(), title = "목록 공연")
        hallId = fixture.hall()
        fixture.seats(hallId, "F1-A", 4)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 50_000))
    }

    @Test
    fun only_my_reservations_newest_first_in_the_list_envelope() {
        val first = hold(me, startsInDays = 3)
        hold(other, startsInDays = 4)
        val second = hold(me, startsInDays = 5)

        val body = json.readTree(
            mvc.get("/api/me/reservations") { with(user(me)) }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
        )

        // 남의 것은 없는 것이다 — 조건이 SQL 에 있다.
        assertThat(body["total"].asInt()).isEqualTo(2)
        val items = body["items"]
        assertThat((0 until items.size()).map { items[it]["reservation_id"].asLong() }).containsExactly(second, first)
        assertThat(body["items"][0]["event_title"].asText()).isEqualTo("목록 공연")
        assertThat(body["items"][0]["seat_count"].asInt()).isEqualTo(1)
        assertThat(body["page"].asInt()).isZero()
    }

    @Test
    fun the_list_pages() {
        hold(me, startsInDays = 3)
        hold(me, startsInDays = 4)
        val newest = hold(me, startsInDays = 5)

        val body = json.readTree(
            mvc.get("/api/me/reservations?page=1&size=2") { with(user(me)) }.andReturn().response.contentAsString,
        )

        assertThat(body["total"].asInt()).isEqualTo(3)
        assertThat(body["size"].asInt()).isEqualTo(2)
        assertThat(body["items"]).hasSize(1)
        assertThat(body["items"][0]["reservation_id"].asLong()).isNotEqualTo(newest)
    }

    /** 회차마다 좌석 하나를 선점한다(계정마다 회차당 진행 중 예매는 하나라 회차를 따로 연다) */
    private fun hold(who: TicketUser, startsInDays: Long): Long {
        val performanceId = fixture.performance(eventId, hallId, startsInDays).also { openService.open(it, actorAccountId = null) }
        return seatHold.hold(who.id, SeatHoldService.Command(performanceId, listOf(fixture.performanceSeatIds(performanceId).first())))
    }

    private fun principal(email: String): TicketUser =
        TicketUser(fixture.account(email), email, AccountRole.AUDIENCE, passwordHash = null, active = true)
}
