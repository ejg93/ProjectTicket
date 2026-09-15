package com.projectticket.ticket.event

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.Snapshot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper

/**
 * 좌석 현황 응답이 `D20` 계약 그대로인가. 좌석도(41)·대기열 화면(43)이 이 모양을 그대로 읽는다.
 *
 * 스냅샷이 계약을 막는다 — 필드를 빼거나 이름을 바꾸면 여기서 빨개지고, 바꾸는 것이면 스냅샷 갱신을 이력에 적는다(`D8`).
 * 「좌석이 바뀌면 버전이 는다」는 여기가 아니라 `SeatVersionTest` 가 잰다 — 롤백 테스트 안에서는 `now()` 가 안 움직인다(`stack.md`).
 */
class SeatQueryTest : PostgresTestBase() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private var eventId: Long = 0
    private var hallId: Long = 0
    private var accountId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        eventId = fixture.event(fixture.organizer())
        hallId = fixture.hall()
        accountId = fixture.account("seat-query@test.local")
    }

    @Test
    fun full_response_matches_the_contract_snapshot() {
        val performanceId = openedPerformance()
        // 상태 셋이 다 보이게 한 자리씩 잡는다. 계정을 가른다 — 같은 계정은 살아있는 선점을 하나만 든다(`V8`).
        fixture.hold(accountId, performanceId, "F1-A", 2)
        fixture.reserve(fixture.hold(fixture.account("seat-query-2@test.local"), performanceId, "F1-B", 1))

        val body = mockMvc.get("/api/performances/$performanceId/seats")
            .andExpect {
                status { isOk() }
                header { exists("ETag") }
                header { string("Cache-Control", "no-cache") }
                jsonPath("$.version") { isNumber() }
                jsonPath("$.sections[0].name") { value("1층 A구역") }
                jsonPath("$.sections[0].rows[0].seats[1].s") { value("H") }
                jsonPath("$.sections[1].rows[0].seats[0].s") { value("R") }
            }
            .andReturn().response.contentAsString

        Snapshot(json, javaClass).assertMatches("full", body)
    }

    @Test
    fun same_version_is_not_modified() {
        val performanceId = openedPerformance()
        val etag = etagOf(performanceId)

        mockMvc.get("/api/performances/$performanceId/seats") { header("If-None-Match", etag) }
            .andExpect {
                status { isNotModified() }
                header { string("ETag", etag) }
                content { string("") }
            }
    }

    @Test
    fun draft_performance_is_not_found() {
        openable()
        val performanceId = fixture.performance(eventId, hallId)

        // 안 열린 회차의 좌석도가 보이면 판매 전에 자리를 고르는 화면이 생긴다. 밖에서는 없는 것이다.
        mockMvc.get("/api/performances/$performanceId/seats")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.type") { value("tag:projectticket.example,2026:performance-not-found") }
            }
    }

    @Test
    fun unknown_performance_is_not_found() {
        mockMvc.get("/api/performances/-1/seats").andExpect { status { isNotFound() } }
    }

    @Test
    fun section_name_is_derived_from_the_code() {
        assertThat(SeatQuery.sectionName("F1-A")).isEqualTo("1층 A구역")
        assertThat(SeatQuery.sectionName("F12-C")).isEqualTo("12층 C구역")
        // 형식은 DB 제약이 강제한다. 여기 걸리면 제약이 빠진 것이고, 그것이 코드 그대로 화면에 가는 것보다 낫다.
        assertThatThrownBy { SeatQuery.sectionName("A구역") }.hasMessageContaining("구역 코드 형식이 아니다")
    }

    /** 구역 둘·등급 둘·좌석 셋 — 그룹핑이 실제로 갈리는 최소 구성 */
    private fun openable() {
        fixture.seats(hallId, "F1-A", 2)
        fixture.seats(hallId, "F1-B", 1)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        fixture.mapSection(eventId, "F1-B", fixture.grade(eventId, "R", 121_000))
    }

    private fun openedPerformance(): Long {
        openable()
        return fixture.performance(eventId, hallId).also { openService.open(it, actorAccountId = null) }
    }

    private fun etagOf(performanceId: Long): String =
        mockMvc.get("/api/performances/$performanceId/seats").andReturn().response.getHeader("ETag")!!  // 200 에는 늘 있다 — 없으면 여기서 죽는 것이 맞다
}
