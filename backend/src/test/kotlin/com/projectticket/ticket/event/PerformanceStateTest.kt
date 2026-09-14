package com.projectticket.ticket.event

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 회차 상태와 등급·가격 제약이 실제로 막는가.
 *
 * **제약 위반은 테스트 하나에 하나다.** 위반이 나면 Postgres 가 트랜잭션을 어보트시켜서
 * 같은 테스트의 다음 문장이 `25P02` 로 죽고, 그러면 재려던 제약이 아니라 어보트를 잰다.
 */
class PerformanceStateTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient

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
    fun draft_can_open_and_open_can_close() {
        val performanceId = fixture.performance(eventId, hallId)

        setStatus(performanceId, "open")
        assertThat(statusOf(performanceId)).isEqualTo("open")

        setStatus(performanceId, "closed")
        assertThat(statusOf(performanceId)).isEqualTo("closed")
    }

    @Test
    fun open_cannot_go_back_to_draft() {
        val performanceId = fixture.performance(eventId, hallId)
        setStatus(performanceId, "open")

        // 되돌리면 이미 복제된 좌석과 팔린 예매가 남은 채로 「아직 안 연 회차」가 된다.
        assertThatThrownBy { setStatus(performanceId, "draft") }
            .hasStackTraceContaining("할 수 없는 회차 상태 전이다")
    }

    @Test
    fun draft_cannot_jump_to_closed() {
        val performanceId = fixture.performance(eventId, hallId)

        assertThatThrownBy { setStatus(performanceId, "closed") }
            .hasStackTraceContaining("할 수 없는 회차 상태 전이다")
    }

    /**
     * 회차는 `draft` 로만 태어난다.
     *
     * **전이 트리거는 `update` 만 본다.** 삽입을 안 막으면 `status = 'open'` 으로 넣어서 좌석이 한 행도 없는 열린 회차가 되고,
     * 그것이 `PerformanceOpenService` 가 막으려고 있는 바로 그 상태다.
     *
     * `performance_status_check` 는 그 아래 그물이다 — 트리거 둘이 `insert`·`update` 를 각각 먼저 잡아서
     * 평소에는 안 보이고, 트리거가 꺼진 자리에서만 값 목록을 막는다.
     */
    @Test
    fun performance_can_only_be_created_as_draft() {
        assertThatThrownBy { insertWithStatus("open") }
            .hasStackTraceContaining("회차는 draft 로만 만들 수 있다")
    }

    @Test
    fun unknown_status_is_rejected_on_insert() {
        assertThatThrownBy { insertWithStatus("paused") }
            .hasStackTraceContaining("회차는 draft 로만 만들 수 있다")
    }

    private fun insertWithStatus(status: String) =
        jdbc.sql(
            """
            insert into performance (event_id, hall_id, starts_at, sales_open_at, status)
            values (:event, :hall, now() + interval '1 day', now(), :status)
            """,
        ).param("event", eventId).param("hall", hallId).param("status", status).update()

    @Test
    fun sales_cannot_open_after_the_show_starts() {
        // 안 막으면 살 수 없는 회차가 만들어지고 대기열·환불 구간이 음수 시간 위에서 계산된다.
        assertThatThrownBy {
            jdbc.sql(
                """
                insert into performance (event_id, hall_id, starts_at, sales_open_at)
                values (:event, :hall, now(), now() + interval '1 day')
                """,
            ).param("event", eventId).param("hall", hallId).update()
        }.hasStackTraceContaining("performance_sales_before_start_check")
    }

    @Test
    fun negative_price_is_rejected() {
        assertThatThrownBy { fixture.grade(eventId, "VIP", -1) }
            .hasStackTraceContaining("seat_grade_price_check")
    }

    @Test
    fun mapping_to_another_events_grade_is_rejected() {
        val otherEvent = fixture.event(fixture.organizer("other-org"), "다른 공연")
        val otherGrade = fixture.grade(otherEvent, "VIP", 154_000)

        // 가리키게 두면 이 공연의 좌석이 남의 공연 가격으로 팔린다.
        assertThatThrownBy { fixture.mapSection(eventId, "F1-A", otherGrade) }
            .hasStackTraceContaining("다른 공연의 등급이다")
    }

    @Test
    fun section_can_be_mapped_once_per_event() {
        val vip = fixture.grade(eventId, "VIP", 154_000)
        val r = fixture.grade(eventId, "R", 121_000)
        fixture.mapSection(eventId, "F1-A", vip)

        assertThatThrownBy { fixture.mapSection(eventId, "F1-A", r) }
            .describedAs("구역 하나가 등급 둘을 가지면 그 구역의 가격이 안 정해진다")
            .hasStackTraceContaining("seat_grade_map_pkey")
    }

    private fun setStatus(performanceId: Long, status: String) =
        jdbc.sql("update performance set status = :status where performance_id = :id")
            .param("status", status).param("id", performanceId).update()

    private fun statusOf(performanceId: Long): String =
        jdbc.sql("select status from performance where performance_id = :id")
            .param("id", performanceId).query(String::class.java).single()
}
