package com.projectticket.ticket.event

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
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
 * 기획사 등록 폼이 고를 목록(`45a-1`). **남의 기획사는 안 보인다** · 홀은 공용이라 다 보이고 구역·좌석 수를 든다 · 관객은 403.
 */
class OrganizerCatalogTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: ObjectMapper

    private lateinit var fixture: EventFixture
    private lateinit var mine: TicketUser
    private var hallId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        val myOrganizer = fixture.organizer("catalog-mine")
        fixture.organizer("catalog-other")
        val accountId = jdbc.sql(
            "insert into account (email, password_hash, display_name, role) values ('catalog@test.local', 'x', '담당자', 'organizer') returning account_id",
        ).query(Long::class.java).single()
        jdbc.sql("insert into organizer_member (organizer_id, account_id) values (:organizer, :account)")
            .param("organizer", myOrganizer).param("account", accountId).update()
        mine = TicketUser(accountId, "catalog@test.local", AccountRole.ORGANIZER, null, true)
        hallId = fixture.hall(name = "카탈로그관", venueName = "카탈로그홀")
        fixture.seats(hallId, "F1-A", 3)
        fixture.seats(hallId, "F2-B", 2)
    }

    @Test
    fun only_my_organizers_are_listed() {
        val body = json.readTree(mvc.get("/api/organizer/organizers") { with(user(mine)) }.andReturn().response.contentAsString)

        assertThat(body.size()).isEqualTo(1)
        assertThat(body[0]["organizer_id"].asLong()).isPositive()
    }

    @Test
    fun halls_carry_their_sections_and_seat_counts() {
        val body = json.readTree(mvc.get("/api/organizer/halls") { with(user(mine)) }.andReturn().response.contentAsString)
        val hall = (0 until body.size()).map { body[it] }.single { it["hall_id"].asLong() == hallId }

        assertThat(hall["venue_name"].asText()).isEqualTo("카탈로그홀")
        assertThat((0 until hall["sections"].size()).map { hall["sections"][it]["code"].asText() to hall["sections"][it]["seat_count"].asInt() })
            .containsExactly("F1-A" to 3, "F2-B" to 2)
    }

    @Test
    fun an_audience_is_forbidden() {
        val audience = TicketUser(fixture.account("catalog-audience@test.local"), "catalog-audience@test.local", AccountRole.AUDIENCE, null, true)

        mvc.get("/api/organizer/halls") { with(user(audience)) }.andExpect {
            status { isForbidden() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:organizer-forbidden") }
        }
    }
}
