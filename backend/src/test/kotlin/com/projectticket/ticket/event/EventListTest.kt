package com.projectticket.ticket.event

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.Snapshot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper

/**
 * 공연 목록·상세 응답이 `D5` 계약 그대로인가(청크 `40a`). 화면(`40b`)이 이 모양을 그대로 읽는다.
 *
 * **여기서 막는 것 둘.** 하나는 껍데기(`items`·`page`·`size`·`total`)와 필드 이름이고,
 * 둘은 **판매 중인 회차가 있는 공연만 보인다**는 규칙이다 — 그것이 깨지면 화면은 멀쩡한데
 * 들어가서야 살 것이 없다는 것을 안다.
 *
 * 로그인 없이 부른다. `SecurityConfig.PUBLIC_PATHS` 에 없으면 401 이라 이 테스트가 먼저 빨개진다.
 */
class EventListTest : PostgresTestBase() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private var eventId: Long = 0
    private var hallId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        eventId = fixture.event(fixture.organizer(), title = "목록 계약 공연")
        hallId = fixture.hall()
    }

    @Test
    fun the_detail_matches_the_contract_snapshot() {
        openedPerformance()

        val body = mockMvc.get("/api/events/$eventId").andReturn().response.contentAsString

        // 식별자와 시각은 실행마다 다르다. 계약에서 고정할 것은 **이름과 구조**다.
        Snapshot(json, javaClass).assertMatches(
            "detail",
            body,
            setOf("event_id", "performance_id", "starts_at", "sales_open_at", "sales_close_at"),
        )
    }

    @Test
    fun only_events_with_an_open_performance_are_listed() {
        val hidden = fixture.event(fixture.organizer("draft-org"), title = "안 연 공연")
        fixture.performance(hidden, hallId)
        openedPerformance()

        val listed = titlesOf(mockMvc.get("/api/events?size=100").andReturn().response.contentAsString)

        // `draft` 는 기획사가 아직 안 연 것이다. 목록에 두면 살 수 없는 줄을 보여주는 것이 된다.
        org.assertj.core.api.Assertions.assertThat(listed).contains("목록 계약 공연").doesNotContain("안 연 공연")
    }

    @Test
    fun an_event_without_open_performances_still_answers() {
        fixture.performance(eventId, hallId)

        // 목록에는 안 보이지만 주소는 있다. 404 로 답하면 「없는 공연」과 「지금 살 것이 없는 공연」이 같아진다.
        mockMvc.get("/api/events/$eventId").andExpect {
            status { isOk() }
            jsonPath("$.performances") { isEmpty() }
        }
    }

    @Test
    fun the_page_size_is_capped() {
        openedPerformance()

        // 상한은 [Paging] 의 생성자에 있다(`D5`). 컨트롤러마다 자르면 새 목록이 생길 때 빠뜨린다.
        mockMvc.get("/api/events?size=500").andExpect {
            status { isOk() }
            jsonPath("$.size") { value(100) }
        }
    }

    @Test
    fun an_unknown_sort_field_is_rejected() {
        // 정렬 필드는 값이 아니라 식별자라 SQL 에 글자로 박힌다. 허용 목록 밖은 400 이다(`D5`·`D14` 「SQL」).
        mockMvc.get("/api/events?sort=price,asc").andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:validation-failed") }
        }
    }

    @Test
    fun an_unknown_sort_direction_is_rejected() {
        // `starts_at,decs` 같은 오타가 조용히 오름차순이 되면 화면이 거꾸로 그려진 줄 모른다.
        mockMvc.get("/api/events?sort=starts_at,decs").andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun an_unknown_event_is_not_found() {
        mockMvc.get("/api/events/99999999").andExpect {
            status { isNotFound() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:event-not-found") }
        }
    }

    private fun titlesOf(body: String): List<String> {
        val items = json.readTree(body).get("items")
        return (0 until items.size()).map { items.get(it).get("title").asString() }
    }

    /** 구역 둘·등급 둘·좌석 셋 — 상세가 등급을 여럿 드는 최소 구성 */
    private fun openedPerformance(): Long {
        fixture.seats(hallId, "F1-A", 2)
        fixture.seats(hallId, "F1-B", 1)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        fixture.mapSection(eventId, "F1-B", fixture.grade(eventId, "R", 121_000))
        return fixture.performance(eventId, hallId).also { openService.open(it, actorAccountId = null) }
    }
}
