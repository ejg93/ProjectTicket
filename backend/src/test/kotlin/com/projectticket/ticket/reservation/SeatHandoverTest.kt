package com.projectticket.ticket.reservation

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 좌석이 예매를 바꿔 가리키지 못한다(`V24`). SQL 을 직접 던진다 — 서비스를 거치면 조건부 UPDATE 가 먼저 막아서 트리거가 도는지를 못 본다(`D14` 축 2).
 *
 * 정상 경로의 두 꼴(null→A, A→null)이 통과하는 것도 같이 본다. 트리거 조건이 넓어지면 선점·해제가 막히는데, 그것은 여기서 먼저 빨개진다.
 * 막히는 경우를 **맨 끝에** 둔다 — 예외가 난 트랜잭션은 다음 문장을 못 돈다(`25P02`).
 */
class SeatHandoverTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private var performanceId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer())
        val hallId = fixture.hall()
        fixture.seats(hallId, "F1-A", 3)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
    }

    @Test
    fun a_held_seat_cannot_move_to_another_reservation() {
        val first = fixture.hold(accountId = fixture.account("first@test.local"), performanceId = performanceId, section = "F1-A", seatNumber = 1)
        val second = fixture.hold(accountId = fixture.account("second@test.local"), performanceId = performanceId, section = "F1-A", seatNumber = 2)
        val held = seatOf(first)
        val free = fixture.performanceSeatIds(performanceId).last()

        assertThat(point(performanceSeatId = free, reservationId = first)).describedAs("null→A 는 선점이다").isEqualTo(1)
        assertThat(release(free)).describedAs("A→null 은 해제다").isEqualTo(1)

        val thrown = catchThrowable { point(performanceSeatId = held, reservationId = second) }
        assertThat(thrown).withFailMessage("트리거가 안 막았다 — 잡힌 좌석이 둘째 예매를 가리키게 됐다").isNotNull()
        assertThat(thrown).hasStackTraceContaining("좌석이 다른 예매로 넘어간다")
    }

    private fun seatOf(reservationId: Long): Long =
        jdbc.sql("select performance_seat_id from performance_seat where reservation_id = :id")
            .param("id", reservationId)
            .query(Long::class.java)
            .single()

    private fun point(performanceSeatId: Long, reservationId: Long): Int =
        jdbc.sql(
            """
            update performance_seat ps
               set status = 'held', held_until = r.held_until, reservation_id = r.reservation_id
              from reservation r
             where r.reservation_id = :reservation and ps.performance_seat_id = :seat
            """,
        ).param("reservation", reservationId).param("seat", performanceSeatId).update()

    private fun release(performanceSeatId: Long): Int =
        jdbc.sql("update performance_seat set status = 'available', held_until = null, reservation_id = null where performance_seat_id = :seat")
            .param("seat", performanceSeatId)
            .update()
}
