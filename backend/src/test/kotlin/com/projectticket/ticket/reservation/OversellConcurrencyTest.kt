package com.projectticket.ticket.reservation

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * 한 좌석이 두 번 팔리지 않는다 — 경쟁이 여러 좌석에 걸쳐 동시에 날 때(청크 51).
 *
 * [SeatHoldConcurrencyTest] 와 다른 점은 입구다. 여기서는 [ReservationService.hold] 를 부른다 — 멱등키와 교착 재시도 층을 지난다.
 * 좌석 10 에 계정 200, 좌석마다 20 이 다툰다.
 *
 * 방어는 세 겹이다 — 조건부 UPDATE 의 `status` 조건, 갱신 행 수 대조, `V24` 트리거. 탐침 셋(`scripts/probes/oversell-*.patch`)이
 * 한 겹씩 빼서 이 시험이 빨개지는지 본다. 실패 문구가 탐침의 판정 근거라 **문구를 바꾸면 탐침도 같이 바꾼다.**
 */
class OversellConcurrencyTest : ConcurrencyTestBase() {

    @Autowired lateinit var reservations: ReservationService
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private var performanceId: Long = 0
    private var seatIds: List<Long> = emptyList()

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        val hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", SEATS)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
        seatIds = fixture.performanceSeatIds(performanceId)
    }

    @Test
    fun no_seat_sold_twice_under_contention() {
        val accounts = (0 until ACCOUNTS).map { fixture.account("$PREFIX$it@test.local") }

        val results = runConcurrently(ACCOUNTS) { index ->
            val command = SeatHoldService.Command(performanceId, listOf(seatIds[index % SEATS]))
            reservations.hold(accounts[index], UUID.randomUUID().toString(), command)
        }

        assertEachSeatHasOneLiveReservation()
        assertThat(results.count { it.isSuccess })
            .withFailMessage { "두 번 팔렸다 — 좌석 $SEATS 에 성공 ${results.count { it.isSuccess }}" }
            .isEqualTo(SEATS)
        assertAllLosersSawSeatTaken(results.mapNotNull { it.exceptionOrNull() })
    }

    /** 좌석마다 살아 있는 예매의 기록이 하나고, 좌석 행이 그 예매를 가리킨다 */
    private fun assertEachSeatHasOneLiveReservation() {
        seatIds.forEach { seat ->
            val live = liveReservationsRecording(seat)
            assertThat(live)
                .withFailMessage { "두 번 팔렸다 — performance_seat_id=$seat 를 살아 있는 예매 $live 가 기록했다" }
                .hasSize(1)
            assertThat(pointerOf(seat))
                .withFailMessage { "두 번 팔렸다 — performance_seat_id=$seat 의 포인터가 기록한 예매 $live 와 다르다" }
                .isEqualTo(live.single())
        }
    }

    /** 진 쪽은 전부 「남이 이겼다」로 끝난다. 다른 예외면 그 메시지를 문구에 싣는다 — 어느 겹이 막았는지가 거기 있다 */
    private fun assertAllLosersSawSeatTaken(failures: List<Throwable>) {
        assertThat(failures).hasSize(ACCOUNTS - SEATS)
        failures.forEach { failure ->
            assertThat((failure as? TicketException)?.code)
                .withFailMessage { "진 쪽이 SEAT_TAKEN 이 아니다: ${failure.javaClass.simpleName} — ${generateSequence(failure) { it.cause }.joinToString(" / ") { it.message.orEmpty() }}" }
                .isEqualTo(ErrorCode.SEAT_TAKEN)
        }
    }

    private fun liveReservationsRecording(performanceSeatId: Long): List<Long> =
        jdbc.sql(
            """
            select r.reservation_id
              from reservation_seat rs join reservation r on r.reservation_id = rs.reservation_id
             where rs.performance_seat_id = :seat and r.status in ('held', 'paying', 'reserved')
            """,
        ).param("seat", performanceSeatId).query(Long::class.java).list().filterNotNull()

    private fun pointerOf(performanceSeatId: Long): Long? =
        jdbc.sql("select reservation_id from performance_seat where performance_seat_id = :seat")
            .param("seat", performanceSeatId)
            .query { rs, _ -> rs.getObject("reservation_id", Long::class.javaObjectType) }
            .single()

    private companion object {
        const val SEATS = 10
        const val ACCOUNTS = 200
    }
}
