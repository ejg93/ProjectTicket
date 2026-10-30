package com.projectticket.ticket.event

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * DB 제약이 막은 기획사 입력이 **어느 칸이 틀렸는지** 400 으로 답하나(`45d-a`, `D5` 「검증 실패는 어느 필드가 틀렸는지 준다」).
 *
 * 전에는 등급 코드 중복·판매 창 위반이 500 이었다. **응답만 본다** — 제약 위반 뒤 롤백 바탕의 합류 트랜잭션은
 * aborted 라 뒤에 `jdbc.sql` 로 세면 그 단언이 죽는다.
 */
class OrganizerEventValidationTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: ObjectMapper

    private lateinit var fixture: EventFixture
    private lateinit var organizer: TicketUser
    private var organizerId: Long = 0
    private var hallId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        hallId = fixture.hall()
        organizerId = fixture.organizer("mine")
        val accountId = jdbc.sql(
            "insert into account (email, password_hash, display_name, role) values ('mine@test.local', 'x', '담당자', 'organizer') returning account_id",
        ).query(Long::class.java).single()
        jdbc.sql("insert into organizer_member (organizer_id, account_id) values (:organizer, :account)")
            .param("organizer", organizerId)
            .param("account", accountId)
            .update()
        organizer = TicketUser(id = accountId, email = "mine@test.local", role = AccountRole.ORGANIZER, passwordHash = null, active = true)
    }

    @Test
    fun a_duplicate_grade_code_points_at_the_grade() {
        createEvent(
            grade(code = "VIP", section = "F1-A"),
            grade(code = "VIP", section = "F1-B"),
        ).andExpect(validationFailedAt("grades[1].code"))
    }

    @Test
    fun a_price_over_the_ceiling_is_rejected() {
        // 상한은 4석 합이 `reservation.total_amount int` 안에 드는 값이다(ADR 0003, `V27`). 넘으면 선점이 500 이었다.
        createEvent(grade(code = "VIP", section = "F1-A", price = 10_000_001))
            .andExpect(validationFailedAt("grades[0].price"))
    }

    @Test
    fun a_section_under_two_grades_points_at_the_grade() {
        createEvent(
            grade(code = "VIP", section = "F1-A"),
            grade(code = "R", section = "F1-A"),
        ).andExpect(validationFailedAt("grades[1].sections"))
    }

    @Test
    fun sales_opening_after_the_default_close_points_at_sales_open_at() {
        val startsAt = OffsetDateTime.now().plusDays(8)

        // 마감을 안 주면 관람 1시간 전이다(`V14` 트리거). 관람 30분 전에 여는 판매는 마감보다 늦다.
        createPerformance(startsAt, salesOpenAt = startsAt.minusMinutes(30)).andExpect(validationFailedAt("sales_open_at"))
    }

    @Test
    fun sales_opening_after_the_show_points_at_sales_open_at() {
        val startsAt = OffsetDateTime.now().plusDays(8)

        // 이름 순으로 `performance_sales_before_start_check`(`V5`)가 창 제약보다 먼저 걸린다 — 그것도 400 이다(마무리 16차 독립 리뷰).
        createPerformance(startsAt, salesOpenAt = startsAt.plusHours(1)).andExpect(validationFailedAt("sales_open_at"))
    }

    @Test
    fun a_given_close_after_the_show_points_at_sales_close_at() {
        val startsAt = OffsetDateTime.now().plusDays(8)

        createPerformance(startsAt, salesOpenAt = OffsetDateTime.now().minusDays(1), salesCloseAt = startsAt.plusMinutes(10))
            .andExpect(validationFailedAt("sales_close_at"))
    }

    @Test
    fun an_unknown_hall_is_rejected_on_its_field() {
        // 없는 홀은 외래키(`V5`)가 막는데 그 위반이 그물에서 500 이었다(`I13-5`). 틀린 칸이 있는 요청이라 400 이다(`D5`).
        val startsAt = OffsetDateTime.now().plusDays(8)

        createPerformance(startsAt, salesOpenAt = OffsetDateTime.now().minusDays(1), hall = -1)
            .andExpect(validationFailedAt("hall_id"))
    }

    private fun createPerformance(
        startsAt: OffsetDateTime,
        salesOpenAt: OffsetDateTime,
        salesCloseAt: OffsetDateTime? = null,
        hall: Long = hallId,
    ): ResultActionsDsl {
        val eventId = createEvent(grade(code = "VIP", section = "F1-A")).andExpect { status { isCreated() } }.andReturn().response.let {
            json.readTree(it.contentAsString)["event_id"].asLong()
        }
        val body = mapOf("hall_id" to hall, "starts_at" to startsAt.toString(), "sales_open_at" to salesOpenAt.toString()) +
            listOfNotNull(salesCloseAt?.let { "sales_close_at" to it.toString() })
        return mvc.post("/api/organizer/events/$eventId/performances") {
            with(user(organizer)); with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(body)
        }
    }

    private fun validationFailedAt(field: String): MockMvcResultMatchersDsl.() -> Unit = {
        status { isBadRequest() }
        jsonPath("$.type") { value("tag:projectticket.example,2026:validation-failed") }
        jsonPath("$.errors[0].field") { value(field) }
    }

    private fun grade(code: String, section: String, price: Int = 154_000) = mapOf("code" to code, "price" to price, "sections" to listOf(section))

    private fun createEvent(vararg grades: Map<String, Any>): ResultActionsDsl =
        mvc.post("/api/organizer/events") {
            with(user(organizer)); with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(
                mapOf("organizer_id" to organizerId, "title" to "겨울 콘서트", "grades" to grades.toList()),
            )
        }
}
