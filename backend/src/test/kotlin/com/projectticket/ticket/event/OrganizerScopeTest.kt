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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * 기획사 입구가 **역할과 소속 둘 다** 보는가(11, `D5`).
 *
 * 역할은 경로 규칙(`SecurityConfig`)이, 소속은 [OrganizerMembership] 이 본다 — **없는 역할은 403, 남의 것은 404** 다.
 * 그 둘을 안 가르면 관객이 경로를 두드려 「그 기획사가 있다」를 알아낸다.
 */
class OrganizerScopeTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: ObjectMapper

    private lateinit var fixture: EventFixture
    private lateinit var mine: TicketUser
    private lateinit var stranger: TicketUser
    private lateinit var audience: TicketUser
    private var myOrganizer: Long = 0
    private var otherOrganizer: Long = 0
    private var hallId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        hallId = fixture.hall()
        myOrganizer = fixture.organizer("mine")
        otherOrganizer = fixture.organizer("other")
        mine = organizerUser("mine@test.local", myOrganizer)
        stranger = organizerUser("stranger@test.local", otherOrganizer)
        audience = TicketUser(fixture.account("audience@test.local"), "audience@test.local", AccountRole.AUDIENCE, null, true)
        fixture.seats(hallId, "F1-A", 2)
    }

    @Test
    fun an_organizer_creates_an_event_with_grades_and_a_performance() {
        val eventId = createEvent(mine, myOrganizer).andExpect { status { isCreated() } }.andReturn().response.let {
            json.readTree(it.contentAsString)["event_id"].asLong()
        }

        // 등급과 매핑이 같은 트랜잭션에 들어간다 — 없으면 회차를 못 연다.
        assertThat(gradeCount(eventId)).isOne()
        assertThat(mappingCount(eventId)).isOne()

        val performanceId = createPerformance(mine, eventId).andExpect { status { isCreated() } }.andReturn().response.let {
            json.readTree(it.contentAsString)["performance_id"].asLong()
        }

        mvc.post("/api/organizer/performances/$performanceId/open") { with(user(mine)); with(csrf()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.seat_count") { value(2) }
            }
    }

    @Test
    fun sales_close_defaults_when_not_given() {
        val eventId = createdEvent(mine, myOrganizer)
        val performanceId = createPerformance(mine, eventId).andReturn().response.let {
            json.readTree(it.contentAsString)["performance_id"].asLong()
        }

        // 안 주면 트리거가 `starts_at - 1h` 를 채운다(`V14`) — 입구가 그 값을 다시 계산하지 않는다.
        assertThat(
            jdbc.sql("select starts_at - sales_close_at = interval '1 hour' from performance where performance_id = :id")
                .param("id", performanceId).query(Boolean::class.java).single(),
        ).isTrue()
    }

    @Test
    fun an_audience_gets_organizer_forbidden() {
        // 역할이 없으면 경로 규칙이 컨트롤러 앞에서 막는다 — 존재를 숨길 자원이 없어 403 이다(`D5`).
        createEvent(audience, myOrganizer).andExpect {
            status { isForbidden() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:organizer-forbidden") }
        }
    }

    @Test
    fun another_organizer_gets_not_found_not_forbidden() {
        // 403 이면 「그 기획사가 있다」가 샌다. 남의 것은 없는 것이다(`D5` 「403 이냐 404 냐」).
        createEvent(stranger, myOrganizer).andExpect {
            status { isNotFound() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:event-not-found") }
        }
    }

    @Test
    fun another_organizers_event_is_not_found() {
        val eventId = createdEvent(mine, myOrganizer)

        createPerformance(stranger, eventId).andExpect {
            status { isNotFound() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:event-not-found") }
        }
    }

    @Test
    fun another_organizers_performance_cannot_be_opened_or_cancelled() {
        val eventId = createdEvent(mine, myOrganizer)
        val performanceId = createPerformance(mine, eventId).andReturn().response.let {
            json.readTree(it.contentAsString)["performance_id"].asLong()
        }

        mvc.post("/api/organizer/performances/$performanceId/open") { with(user(stranger)); with(csrf()) }
            .andExpect { status { isNotFound() } }
        mvc.post("/api/organizer/performances/$performanceId/cancel") { with(user(stranger)); with(csrf()) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun an_event_without_grades_is_rejected_at_the_door() {
        // 등급 없는 공연은 회차를 못 연다 — 그 상태를 만들지 않는다.
        mvc.post("/api/organizer/events") {
            with(user(mine)); with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("organizer_id" to myOrganizer, "title" to "등급 없는 공연", "grades" to emptyList<Any>()))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:validation-failed") }
        }
    }

    @Test
    fun logging_out_is_401_not_403() {
        // 로그인이 안 됐으면 자원과 무관하게 401 이다(`D5`).
        mvc.post("/api/organizer/events") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = "{}" }
            .andExpect { status { isUnauthorized() } }
    }

    private fun createdEvent(as_: TicketUser, organizerId: Long): Long =
        createEvent(as_, organizerId).andReturn().response.let { json.readTree(it.contentAsString)["event_id"].asLong() }

    private fun createEvent(as_: TicketUser, organizerId: Long): ResultActionsDsl =
        mvc.post("/api/organizer/events") {
            with(user(as_)); with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(
                mapOf(
                    "organizer_id" to organizerId,
                    "title" to "겨울 콘서트",
                    "grades" to listOf(mapOf("code" to "VIP", "price" to 154_000, "sections" to listOf("F1-A"))),
                ),
            )
        }

    private fun createPerformance(as_: TicketUser, eventId: Long): ResultActionsDsl =
        mvc.post("/api/organizer/events/$eventId/performances") {
            with(user(as_)); with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(
                mapOf(
                    "hall_id" to hallId,
                    "starts_at" to OffsetDateTime.now().plusDays(8).toString(),
                    "sales_open_at" to OffsetDateTime.now().minusDays(1).toString(),
                ),
            )
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

    private fun gradeCount(eventId: Long): Long =
        jdbc.sql("select count(*) from seat_grade where event_id = :id").param("id", eventId).query(Long::class.java).single()

    private fun mappingCount(eventId: Long): Long =
        jdbc.sql("select count(*) from seat_grade_map where event_id = :id").param("id", eventId).query(Long::class.java).single()
}
