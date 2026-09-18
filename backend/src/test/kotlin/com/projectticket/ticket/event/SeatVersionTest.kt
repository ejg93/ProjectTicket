package com.projectticket.ticket.event

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.reservation.SeatHoldService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 좌석이 바뀌면 버전이 움직이는가.
 *
 * **롤백 없는 바탕에서 잰다**(`10a` 뒤로는 더 그렇다) — 판을 올리는 자리가 `afterCommit` 이라 커밋이 없으면 아무 일도 안 난다.
 * 그리고 판을 올리는 것은 **서비스 경로**다: 생 SQL 로 좌석을 바꾸면 DB 는 바뀌지만 Redis 는 모른다(`D20` — DB 가 진실, Redis 는 순서).
 */
class SeatVersionTest : ConcurrencyTestBase() {

    @Autowired lateinit var seatQuery: SeatQuery
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var seatHoldService: SeatHoldService

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
        val seatId = fixture.performanceSeatIds(performanceId).first()

        // 선점 서비스가 커밋하고, 그 커밋 뒤에 판이 오른다(`D20`).
        seatHoldService.hold(
            fixture.account("${PREFIX}viewer@test.local"),
            SeatHoldService.Command(performanceId, listOf(seatId)),
        )

        assertThat(seatQuery.version(performanceId))
            .describedAs("버전이 그대로면 폴링이 304 만 받아 잡힌 좌석이 화면에서 빈자리로 남는다")
            .isGreaterThan(before)
    }
}
