package com.projectticket.ticket.reservation

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.payment.MockPaymentGateway
import com.projectticket.ticket.payment.PaymentTransitionService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 판매 마감이 지나면 회차가 닫히고 **남은 선점만** 풀리는가(16a, `D3` 「자동 전이 셋」).
 *
 * 마감은 **이미 지난 시각을 직접 넣어** 만든다(`D8`) — 시계가 DB 라 앱에서 기다릴 방법이 없다.
 * 스케줄러는 테스트에서 꺼져 있다(`SchedulingConfig`) — 회차를 손으로 부른다.
 */
class PerformanceCloseTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var closer: PerformanceCloser
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var payments: PaymentTransitionService

    private lateinit var fixture: EventFixture
    private var eventId: Long = 0
    private var hallId: Long = 0
    private var performanceId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        eventId = fixture.event(fixture.organizer())
        hallId = fixture.hall()
        fixture.seats(hallId, "F1-A", 3)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId).also { openService.open(it, actorAccountId = null) }
    }

    @Test
    fun sales_close_at_defaults_to_an_hour_before_the_show() {
        // 입구가 값을 안 주면 트리거가 채운다(`V14`). 앱이 계산하면 입구가 늘 때 하나가 빠뜨린다.
        assertThat(
            jdbc.sql("select starts_at - sales_close_at = interval '1 hour' from performance where performance_id = :id")
                .param("id", performanceId).query(Boolean::class.java).single(),
        ).isTrue()
    }

    @Test
    fun a_given_close_time_is_kept() {
        val given = jdbc.sql(
            """
            insert into performance (event_id, hall_id, starts_at, sales_open_at, sales_close_at)
            values (:event, :hall, now() + interval '2 days', now() - interval '1 day', now() + interval '1 day')
            returning performance_id
            """,
        ).param("event", eventId).param("hall", hallId).query(Long::class.java).single()

        assertThat(
            jdbc.sql("select starts_at - sales_close_at > interval '1 hour' from performance where performance_id = :id")
                .param("id", given).query(Boolean::class.java).single(),
        ).describedAs("기획사가 준 값을 트리거가 덮으면 안 된다").isTrue()
    }

    @Test
    fun the_sales_window_must_be_ordered() {
        // 순서가 어긋나면 살 수 없는 회차이거나 관람 뒤에도 파는 회차가 된다.
        assertThatThrownBy {
            jdbc.sql(
                """
                insert into performance (event_id, hall_id, starts_at, sales_open_at, sales_close_at)
                values (:event, :hall, now() + interval '2 days', now(), now() + interval '3 days')
                """,
            ).param("event", eventId).param("hall", hallId).update()
        }.hasStackTraceContaining("performance_sales_window_check")
    }

    @Test
    fun held_seats_expire_on_close() {
        val accountId = fixture.account("closing@test.local")
        val reservationId = seatHold.hold(accountId, SeatHoldService.Command(performanceId, fixture.performanceSeatIds(performanceId).take(2)))
        passTheDeadline()

        assertThat(closer.closeDue()).isEqualTo(1)

        assertThat(statusOf(performanceId)).isEqualTo("closed")
        assertThat(reservationStatus(reservationId)).isEqualTo("expired")
        // 좌석이 안 돌아오면 닫힌 회차에 영영 잡힌 자리가 남는다 — 정산도 그것을 판 것으로 세지 않는다.
        assertThat(availableSeatCount()).isEqualTo(3)
        assertThat(eventCount("performance.closed")).isOne()
    }

    @Test
    fun paying_reservations_expire_too() {
        val accountId = fixture.account("paying-close@test.local")
        val reservationId = seatHold.hold(accountId, SeatHoldService.Command(performanceId, fixture.performanceSeatIds(performanceId).take(1)))
        payments.startPaying(accountId, reservationId)
        passTheDeadline()

        closer.closeDue()

        // 결제 중이어도 마감이면 끝이다(`D3` 전이표 — `held`·`paying` → `expired`). 승인이 뒤늦게 오면 `payment_late` 다.
        assertThat(reservationStatus(reservationId)).isEqualTo("expired")
        assertThat(availableSeatCount()).isEqualTo(3)
    }

    @Test
    fun reserved_reservations_survive_the_close() {
        val accountId = fixture.account("reserved-close@test.local")
        val reservationId = seatHold.hold(accountId, SeatHoldService.Command(performanceId, fixture.performanceSeatIds(performanceId).take(1)))
        val paying = payments.startPaying(accountId, reservationId)
        payments.settle(accountId, paying, MockPaymentGateway.Result("M-${System.nanoTime()}", "4242", null))
        passTheDeadline()

        closer.closeDue()

        // 닫는 것은 **파는 일**이지 판 것을 무르는 일이 아니다. 무르는 것은 회차 취소(17a)고 전액 환불이 따라온다.
        assertThat(reservationStatus(reservationId)).isEqualTo("reserved")
        assertThat(availableSeatCount()).isEqualTo(2)
    }

    @Test
    fun closing_is_idempotent_and_skips_performances_before_their_deadline() {
        passTheDeadline()

        assertThat(closer.closeDue()).isEqualTo(1)
        // 두 대가 같이 돌아도 둘째는 0행이다. 사건이 두 번 나가면 정산서가 둘이 된다.
        assertThat(closer.closeDue()).isZero()
        assertThat(eventCount("performance.closed")).isOne()
    }

    @Test
    fun a_performance_before_its_deadline_is_untouched() {
        assertThat(closer.closeDue()).isZero()
        assertThat(statusOf(performanceId)).isEqualTo("open")
    }

    /** 마감을 이미 지난 시각으로. `sales_close_at` 은 트리거가 없어 `update` 로 되돌아간다(`stack.md`) */
    private fun passTheDeadline() =
        jdbc.sql("update performance set sales_close_at = now() - interval '1 second' where performance_id = :id")
            .param("id", performanceId).update()

    private fun statusOf(performanceId: Long): String =
        jdbc.sql("select status from performance where performance_id = :id").param("id", performanceId).query(String::class.java).single()

    private fun reservationStatus(reservationId: Long): String =
        jdbc.sql("select status from reservation where reservation_id = :id").param("id", reservationId).query(String::class.java).single()

    private fun availableSeatCount(): Long =
        jdbc.sql("select count(*) from performance_seat where performance_id = :id and status = 'available'")
            .param("id", performanceId).query(Long::class.java).single()

    private fun eventCount(type: String): Long =
        jdbc.sql("select count(*) from outbox where type = :type and aggregate_id = :id")
            .param("type", type).param("id", performanceId).query(Long::class.java).single()
}
