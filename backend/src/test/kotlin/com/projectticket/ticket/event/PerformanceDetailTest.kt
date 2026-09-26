package com.projectticket.ticket.event

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
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
import java.time.OffsetDateTime

/**
 * 회차 등록 201 의 `Location` 이 **실제로 답하는가**(`40c`, RFC 9110).
 *
 * 전에는 `Location` 이 가리키는 주소가 없어 404 였다 — 헤더는 맞게 나가는데 따라가면 끊긴다. 흐름 시험은
 * `Location` 을 안 따라가서 초록이었다. 여기서는 **받은 헤더를 그대로** GET 한다.
 */
class PerformanceDetailTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private lateinit var organizer: TicketUser
    private var organizerId: Long = 0
    private var hallId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        hallId = fixture.hall()
        organizerId = fixture.organizer("detail-org")
        val accountId = jdbc.sql(
            "insert into account (email, password_hash, display_name, role) values ('detail@test.local', 'x', '담당자', 'organizer') returning account_id",
        ).query(Long::class.java).single()
        jdbc.sql("insert into organizer_member (organizer_id, account_id) values (:organizer, :account)")
            .param("organizer", organizerId).param("account", accountId).update()
        organizer = TicketUser(accountId, "detail@test.local", AccountRole.ORGANIZER, null, true)
        fixture.seats(hallId, "F1-A", 2)
    }

    @Test
    fun the_location_of_a_created_performance_answers() {
        val eventId = mvc.post("/api/organizer/events") {
            with(user(organizer)); with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(
                mapOf(
                    "organizer_id" to organizerId,
                    "title" to "회차 조회 공연",
                    "grades" to listOf(mapOf("code" to "VIP", "price" to 154_000, "sections" to listOf("F1-A"))),
                ),
            )
        }.andReturn().response.let { json.readTree(it.contentAsString)["event_id"].asLong() }

        val created = mvc.post("/api/organizer/events/$eventId/performances") {
            with(user(organizer)); with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(
                mapOf(
                    "hall_id" to hallId,
                    "starts_at" to OffsetDateTime.now().plusDays(8).toString(),
                    "sales_open_at" to OffsetDateTime.now().minusDays(1).toString(),
                ),
            )
        }.andExpect { status { isCreated() } }.andReturn().response
        val performanceId = json.readTree(created.contentAsString)["performance_id"].asLong()
        val location = created.getHeader("Location")

        // 받은 헤더를 그대로 따라간다. 막 만든 회차는 `draft` 라 등록한 기획사에게는 답해야 한다.
        assertThat(location).isEqualTo("/api/performances/$performanceId")
        mvc.get(checkNotNull(location)) { with(user(organizer)) }.andExpect {
            status { isOk() }
            jsonPath("$.performance_id") { value(performanceId) }
            jsonPath("$.event_id") { value(eventId) }
            jsonPath("$.title") { value("회차 조회 공연") }
            jsonPath("$.status") { value("draft") }
            jsonPath("$.grades[0].code") { value("VIP") }
            jsonPath("$.grades[0].price") { value(154_000) }
            jsonPath("$.hall_name") { exists() }
            jsonPath("$.venue_name") { exists() }
        }
    }

    @Test
    fun a_draft_is_hidden_from_everyone_but_its_organizer() {
        val draft = fixture.performance(fixture.event(organizerId), hallId)
        val stranger = TicketUser(fixture.account("stranger@test.local"), "stranger@test.local", AccountRole.AUDIENCE, null, true)

        // 번호가 순번이라 훑으면 미공개 공연이 드러난다. 남에게는 없는 회차와 한 이름이다(`D5`).
        mvc.get("/api/performances/$draft").andExpect {
            status { isNotFound() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:performance-not-found") }
        }
        mvc.get("/api/performances/$draft") { with(user(stranger)) }.andExpect { status { isNotFound() } }
        mvc.get("/api/performances/$draft") { with(user(organizer)) }.andExpect { status { isOk() } }
    }

    @Test
    fun a_missing_performance_is_not_found() {
        mvc.get("/api/performances/-1").andExpect {
            status { isNotFound() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:performance-not-found") }
        }
    }

    @Test
    fun it_is_public_but_the_path_below_it_is_not() {
        val eventId = fixture.event(organizerId)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 1_000))
        val performanceId = fixture.performance(eventId, hallId).also { openService.open(it, actorAccountId = null) }

        // 연 회차는 로그인 없이 본다 — 보고 나서 로그인한다.
        mvc.get("/api/performances/$performanceId").andExpect { status { isOk() } }
        // `PUBLIC_GET_PATHS` 의 `*` 가 두 단계를 먹으면 선점이 열린다. 한 단계인지 여기서 본다.
        mvc.post("/api/performances/$performanceId/reservations") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"seat_ids":[1]}"""
        }.andExpect { status { isUnauthorized() } }
    }
}
