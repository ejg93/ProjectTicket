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
 */
class SeatQueryTest : PostgresTestBase() {

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
        eventId = fixture.event(fixture.organizer())
        hallId = fixture.hall()
    }

    @Test
    fun full_response_matches_the_contract_snapshot() {
        val performanceId = openedPerformance()
        // 상태 셋이 다 보이게 한 자리씩 잡는다. 예매 표는 13 이 만들므로 `reservation_id` 는 아직 아무 값이나 된다.
        markSeat(performanceId, "F1-A", 2, "held")
        markSeat(performanceId, "F1-B", 1, "reserved")

        val body = mockMvc.get("/api/performances/$performanceId/seats")
            .andExpect {
                status { isOk() }
                header { exists("ETag") }
                header { string("Cache-Control", "no-cache") }
                jsonPath("$.version") { isNumber() }
                jsonPath("$.sections[0].name") { value("1층 A구역") }
                jsonPath("$.sections[0].rows[0].seats[1].s") { value("H") }
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

    private fun markSeat(performanceId: Long, section: String, number: Int, status: String) {
        jdbc.sql(
            """
            update performance_seat ps
               set status = :status,
                   held_until = case when :status = 'held' then now() + interval '5 minutes' end,
                   reservation_id = 1
              from seat s
             where s.seat_id = ps.seat_id
               and ps.performance_id = :performanceId
               and s.section = :section and s.seat_number = :number
            """,
        ).param("status", status).param("performanceId", performanceId).param("section", section).param("number", number).update()
    }
}
