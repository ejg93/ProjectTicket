package com.projectticket.ticket.reservation

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 스윕이 **만료된 `held` 만** 되돌리는가. 결제 중(`paying`)·확정(`reserved`)·아직 살아있는 선점은 손대지 않는다(`D4` 「스윕과 확정의 경합」).
 *
 * 만료는 **이미 지난 행을 직접 넣어** 만든다(`D8`) — 시계가 DB 라 앱 시계를 고정해도 `now()` 는 안 움직이고, `Thread.sleep` 은 운영과 다른 값을 잰다.
 */
class HoldSweeperTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var sweeper: HoldSweeper
    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private var accountId: Long = 0
    private var performanceId: Long = 0
    private var seatIds: List<Long> = emptyList()

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        accountId = fixture.account("sweep@test.local")
        val eventId = fixture.event(fixture.organizer())
        val hallId = fixture.hall()
        fixture.seats(hallId, "F1-A", 4)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
        seatIds = fixture.performanceSeatIds(performanceId)
    }

    @Test
    fun expired_hold_is_released() {
        val reservationId = hold(seatIds.take(2))
        expire(reservationId)

        assertThat(sweeper.sweep()).isEqualTo(1)

        assertThat(reservationRow(reservationId)).isEqualTo("expired" to true)
        assertThat(seatRows(seatIds.take(2))).allSatisfy { assertThat(it).isEqualTo("available" to null) }
        assertThat(auditCount("reservation.expired", reservationId)).isOne()
        // 기록은 남는다 — 무엇을 잡았었는지는 `reservation_seat` 가 답한다(`D4` 「두 표의 역할」).
        assertThat(jdbc.sql("select count(*) from reservation_seat where reservation_id = :id").param("id", reservationId).query(Long::class.java).single()).isEqualTo(2)
    }

    @Test
    fun does_not_release_confirmed() {
        val paying = hold(seatIds.take(1))
        jdbc.sql("update reservation set status = 'paying', paying_until = now() + interval '3 minutes' where reservation_id = :id").param("id", paying).update()
        expire(paying)
        val reserved = fixture.hold(accountId, performanceId, "F1-A", 2).also { fixture.reserve(it); expire(it) }

        // 스윕이 T 에 좌석을 풀고 승인이 T+ε 에 오면 돈은 받고 좌석은 없는 예매가 된다(ADR 0004). `paying` 은 스윕의 where 에 안 걸린다.
        assertThat(sweeper.sweep()).isZero()

        assertThat(reservationRow(paying)).isEqualTo("paying" to false)
        assertThat(seatRows(seatIds.take(1))).containsExactly("held" to paying)
        assertThat(reservationRow(reserved)).isEqualTo("reserved" to false)
        assertThat(seatRows(seatIds.drop(1).take(1))).containsExactly("reserved" to reserved)
    }

    @Test
    fun live_hold_is_untouched() {
        val reservationId = hold(seatIds.take(1))

        assertThat(sweeper.sweep()).isZero()
        assertThat(reservationRow(reservationId)).isEqualTo("held" to false)
    }

    @Test
    fun sweep_is_idempotent() {
        expire(hold(seatIds.take(1)))

        assertThat(sweeper.sweep()).isEqualTo(1)
        // 두 대가 같이 돌아도 둘째는 0행이다(`D3` 「자동 전이 셋」).
        assertThat(sweeper.sweep()).isZero()
    }

    private fun hold(seats: List<Long>): Long = seatHold.hold(accountId, SeatHoldService.Command(performanceId, seats))

    /** `held_until` 을 이미 지난 시각으로. `set_updated_at` 은 `updated_at` 만 덮으므로 이 열은 그대로 남는다 */
    private fun expire(reservationId: Long) =
        jdbc.sql("update reservation set held_until = now() - interval '1 second' where reservation_id = :id").param("id", reservationId).update()

    private fun reservationRow(reservationId: Long): Pair<String, Boolean> =
        jdbc.sql("select status, expired_at is not null as expired from reservation where reservation_id = :id")
            .param("id", reservationId)
            .query { rs, _ -> rs.getString("status") to rs.getBoolean("expired") }
            .single()

    private fun seatRows(performanceSeatIds: List<Long>): List<Pair<String, Long?>> =
        jdbc.sql("select status, reservation_id from performance_seat where performance_seat_id in (:ids) order by performance_seat_id")
            .param("ids", performanceSeatIds)
            .query { rs, _ -> rs.getString("status") to rs.getObject("reservation_id", Long::class.javaObjectType) }
            .list()

    private fun auditCount(eventType: String, targetId: Long): Long =
        jdbc.sql("select count(*) from audit_log where event_type = :type and target_id = :id")
            .param("type", eventType).param("id", targetId).query(Long::class.java).single()
}
