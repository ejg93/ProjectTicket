package com.projectticket.ticket.payment

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.outbox.OutboxRelay
import com.projectticket.ticket.reservation.PerformanceCloser
import com.projectticket.ticket.reservation.SeatHoldService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 기획사가 회차를 취소하면 **판 것까지 무른다**(17a, `D3`·`D6`). 자동 종료(16a)가 `reserved` 를 안 건드리는 것과 반대 자리다.
 *
 * **커밋 레인이다.** 사건·환불이 소비자(`REQUIRES_NEW`)와 스윕을 거치고 지연 트리거가 커밋 때 돈다(`stack.md`).
 */
class PerformanceCancelTest : ConcurrencyTestBase() {

    @Autowired lateinit var cancelService: PerformanceCancelService
    @Autowired lateinit var refundSweeper: RefundSweeper
    @Autowired lateinit var relay: OutboxRelay
    @Autowired lateinit var closer: PerformanceCloser
    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var payments: PaymentTransitionService
    @Autowired lateinit var refunds: RefundTransitionService
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private var eventId: Long = 0
    private var hallId: Long = 0
    private var performanceId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        eventId = fixture.event(fixture.organizer("${PREFIX}org"), "취소될 공연")
        hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 4)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId, startsInDays = 8).also { openService.open(it, actorAccountId = null) }
    }

    @Test
    fun every_reservation_refunded_in_full() {
        val buyer = fixture.account("${PREFIX}buyer@test.local")
        val reservationId = reserve(buyer, seats = 2)

        assertThat(cancelService.cancel(performanceId, actorAccountId = null)).isEqualTo(1)

        assertThat(statusOf()).isEqualTo("cancelled")
        assertThat(reservationStatus(reservationId)).isEqualTo("cancelled")
        // 구간과 무관하게 전액이다(`D6` 「사유별」) — 관객이 고른 시점이 아니라 회차가 사라진 것이다.
        val refund = refundOf(reservationId)
        assertThat(refund).isEqualTo(Refund("performance_cancelled", 0, 0, 308_000, "requested"))
        assertThat(freeSeatCount()).isEqualTo(4)
    }

    @Test
    fun held_and_paying_are_cancelled_without_a_refund() {
        val holder = fixture.account("${PREFIX}holder@test.local")
        val held = seatHold.hold(holder, SeatHoldService.Command(performanceId, seatIds().take(1)))
        val payer = fixture.account("${PREFIX}payer@test.local")
        val paying = seatHold.hold(payer, SeatHoldService.Command(performanceId, seatIds().drop(1).take(1)))
        payments.startPaying(payer, paying)

        assertThat(cancelService.cancel(performanceId, actorAccountId = null)).isEqualTo(2)

        // 돈이 안 움직인 예매는 환불이 없다 — 결제 행이 `approved` 가 아니라서다(`D6` 「결제 전 만료는 환불이 아니다」와 같은 논리).
        assertThat(reservationStatus(held)).isEqualTo("cancelled")
        assertThat(reservationStatus(paying)).isEqualTo("cancelled")
        assertThat(refundCount()).isZero()
        assertThat(freeSeatCount()).isEqualTo(4)
    }

    @Test
    fun a_reservation_the_audience_already_cancelled_keeps_its_own_refund() {
        val early = fixture.account("${PREFIX}early@test.local")
        val alreadyCancelled = reserve(early, seats = 1)
        refunds.request(early, alreadyCancelled)
        val late = fixture.account("${PREFIX}late@test.local")
        reserve(late, seats = 1, from = 1)

        cancelService.cancel(performanceId, actorAccountId = null)

        // 결제당 환불은 하나다(`D6`). 관객이 먼저 취소했으면 그 수수료가 남는다 — 회차 취소가 덮어쓰면 이미 뗀 돈이 사라진다.
        assertThat(refundOf(alreadyCancelled).reason).isEqualTo("audience")
        assertThat(refundOf(alreadyCancelled).feeAmount).isEqualTo(15_400)
        assertThat(refundCount()).isEqualTo(2)
    }

    @Test
    fun the_sweeper_sends_what_the_cancellation_queued() {
        reserve(fixture.account("${PREFIX}buyer@test.local"), seats = 1)
        cancelService.cancel(performanceId, actorAccountId = null)

        assertThat(refundSweeper.sweep()).isEqualTo(1)

        // PG 를 취소 트랜잭션에서 안 부른다(`D4`) — 스윕이 트랜잭션 밖에서 보낸다.
        assertThat(refundStatusOf()).isEqualTo("done")
        // 같은 키로 다시 보내도 돈이 두 번 안 나간다. 두 번째 회는 집을 것이 없다.
        assertThat(refundSweeper.sweep()).isZero()
    }

    @Test
    fun cancelling_emits_one_event_that_mails_everyone() {
        val first = fixture.account("${PREFIX}first@test.local")
        val second = fixture.account("${PREFIX}second@test.local")
        reserve(first, seats = 1)
        reserve(second, seats = 1, from = 1)
        cancelService.cancel(performanceId, actorAccountId = null)

        relay.relay()

        // 사건은 하나고 수신자가 둘이다(`D11`) — 소비자가 예매자를 표에서 읽는다.
        assertThat(eventCount()).isOne()
        assertThat(mailCount()).isEqualTo(2)
        assertThat(anyMailBody()).contains("공연이 취소되어").contains("154,000원 (전액)")
    }

    @Test
    fun a_closed_performance_cannot_be_cancelled() {
        jdbc.sql("update performance set sales_close_at = now() - interval '1 second' where performance_id = :id").param("id", performanceId).update()
        closer.closeDue()

        // `closed` 뒤의 취소는 없다(`D3`) — 정산이 이미 그 상태를 최종으로 본다.
        assertThatThrownBy { cancelService.cancel(performanceId, actorAccountId = null) }
            .isInstanceOf(TicketException::class.java)
            .satisfies({ e ->
                e as TicketException
                assertThat(e.code).isEqualTo(ErrorCode.INVALID_TRANSITION)
                assertThat(e.properties).containsEntry("from", "closed")
            })
    }

    @Test
    fun cancelling_twice_is_rejected() {
        cancelService.cancel(performanceId, actorAccountId = null)

        assertThatThrownBy { cancelService.cancel(performanceId, actorAccountId = null) }
            .isInstanceOf(TicketException::class.java).extracting("code").isEqualTo(ErrorCode.INVALID_TRANSITION)
        assertThat(eventCount()).describedAs("사건이 둘이면 메일이 두 번 간다").isOne()
    }

    private fun reserve(accountId: Long, seats: Int, from: Int = 0): Long {
        val reservationId = seatHold.hold(accountId, SeatHoldService.Command(performanceId, seatIds().drop(from).take(seats)))
        val paying = payments.startPaying(accountId, reservationId)
        payments.settle(accountId, paying, MockPaymentGateway.Result("M-${System.nanoTime()}", "4242", null))
        return reservationId
    }

    private fun seatIds(): List<Long> = fixture.performanceSeatIds(performanceId)

    private fun statusOf(): String =
        jdbc.sql("select status from performance where performance_id = :id").param("id", performanceId).query(String::class.java).single()

    private fun reservationStatus(reservationId: Long): String =
        jdbc.sql("select status from reservation where reservation_id = :id").param("id", reservationId).query(String::class.java).single()

    private fun refundOf(reservationId: Long): Refund =
        jdbc.sql(
            """
            select rf.reason, rf.days_before, rf.fee_amount, rf.refund_amount, rf.status
              from refund rf join payment pm on pm.payment_id = rf.payment_id
             where pm.reservation_id = :id
            """,
        ).param("id", reservationId).query(Refund::class.java).single()

    private fun refundCount(): Long =
        jdbc.sql(
            "select count(*) from refund rf join payment pm on pm.payment_id = rf.payment_id join reservation r on r.reservation_id = pm.reservation_id where r.performance_id = :id",
        ).param("id", performanceId).query(Long::class.java).single()

    private fun refundStatusOf(): String =
        jdbc.sql(
            "select rf.status from refund rf join payment pm on pm.payment_id = rf.payment_id join reservation r on r.reservation_id = pm.reservation_id where r.performance_id = :id",
        ).param("id", performanceId).query(String::class.java).single()

    private fun freeSeatCount(): Long =
        jdbc.sql("select count(*) from performance_seat where performance_id = :id and status = 'available'")
            .param("id", performanceId).query(Long::class.java).single()

    private fun eventCount(): Long =
        jdbc.sql("select count(*) from outbox where type = 'performance.cancelled' and aggregate_id = :id")
            .param("id", performanceId).query(Long::class.java).single()

    private fun mailCount(): Long =
        jdbc.sql("select count(*) from notification where event_type = 'performance.cancelled'").query(Long::class.java).single()

    private fun anyMailBody(): String =
        jdbc.sql("select body from notification where event_type = 'performance.cancelled' limit 1").query(String::class.java).single()

    data class Refund(val reason: String, val daysBefore: Int, val feeAmount: Int, val refundAmount: Int, val status: String)
}
