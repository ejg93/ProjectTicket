package com.projectticket.ticket.event

import com.projectticket.ticket.ConcurrencyTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 좌석이 바뀌면 버전이 움직이는가. **롤백 없는 바탕에서 잰다** — 버전이 `updated_at` 의 최댓값이고 `now()` 는 트랜잭션 시작 시각이라,
 * 한 트랜잭션에 묶인 테스트 안에서는 오픈과 선점의 `updated_at` 이 같아서 버전이 안 움직인다(`stack.md`).
 * 운영은 선점마다 트랜잭션이 따로라 이 바탕이 실물에 가깝다.
 */
class SeatVersionTest : ConcurrencyTestBase() {

    @Autowired lateinit var seatQuery: SeatQuery
    @Autowired lateinit var openService: PerformanceOpenService

    @Test
    fun committed_seat_change_moves_the_version() {
        val fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        val hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        val performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
        val before = seatQuery.version(performanceId)

        // 자동 커밋이라 이 문장이 자기 트랜잭션이다. 예매 표는 13 이 만들므로 `reservation_id` 는 아직 아무 값이나 된다.
        jdbc.sql(
            """
            update performance_seat
               set status = 'held', held_until = now() + interval '5 minutes', reservation_id = 1
             where performance_id = :id and performance_seat_id = (
                   select min(performance_seat_id) from performance_seat where performance_id = :id)
            """,
        ).param("id", performanceId).update()

        assertThat(seatQuery.version(performanceId))
            .describedAs("버전이 그대로면 폴링이 304 만 받아 잡힌 좌석이 화면에서 빈자리로 남는다")
            .isGreaterThan(before)
    }
}
