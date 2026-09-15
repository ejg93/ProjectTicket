package com.projectticket.ticket.payment

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.reservation.HoldSweeper
import com.projectticket.ticket.reservation.SeatHoldService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 결제 전이 둘(①·③)과 스윕의 경합(`D4` 「스윕과 확정의 경합」). PG 는 여기 없다 — 결과([MockPaymentGateway.Result])를 직접 넣는다.
 *
 * 「승인이 늦었다」는 `paying_until` 을 **이미 지난 시각으로 직접 넣어** 만든다(`D8`). 시계가 DB 라 앱에서 기다릴 방법이 없다.
 */
class PaymentConfirmTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var transitions: PaymentTransitionService
    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var sweeper: HoldSweeper
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private var accountId: Long = 0
    private var performanceId: Long = 0
    private var seatIds: List<Long> = emptyList()

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        accountId = fixture.account("payer@test.local")
        val eventId = fixture.event(fixture.organizer())
        val hallId = fixture.hall()
        fixture.seats(hallId, "F1-A", 4)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
        seatIds = fixture.performanceSeatIds(performanceId)
    }

    @Test
    fun approval_confirms_reservation_and_seats() {
        val paying = transitions.startPaying(accountId, hold(2))
        assertThat(paying.amount).isEqualTo(308_000)
        assertThat(reservation(paying.reservationId)).isEqualTo("paying")

        val settled = transitions.settle(accountId, paying, approved())

        assertThat(settled.late).isFalse()
        assertThat(settled.reservationStatus).isEqualTo("reserved")
        assertThat(reservation(paying.reservationId)).isEqualTo("reserved")
        assertThat(seatStatuses(paying.reservationId)).containsOnly("reserved")
        assertThat(paymentRow(settled.paymentId)).isEqualTo("approved" to 308_000)
        assertThat(auditCount("reservation.reserved", paying.reservationId)).isOne()
    }

    @Test
    fun expired_hold_cannot_confirm() {
        val paying = transitions.startPaying(accountId, hold(1))
        // 타임아웃이 먼저 갈 자리. 승인의 조건부 UPDATE 는 `paying_until >= now()` 라 여기서 0행이다.
        jdbc.sql("update reservation set paying_until = now() - interval '1 second' where reservation_id = :id").param("id", paying.reservationId).update()

        val settled = transitions.settle(accountId, paying, approved())

        // 돈은 받았고 좌석은 못 줬다 — 결제 행은 승인으로 남고(환불 17 이 이것을 가리킨다) 예매는 그대로다.
        assertThat(settled.late).isTrue()
        assertThat(settled.reservationStatus).isEqualTo("paying")
        assertThat(seatStatuses(paying.reservationId)).containsOnly("held")
        assertThat(paymentRow(settled.paymentId)).isEqualTo("approved" to 154_000)
        assertThat(auditCount("payment.late", settled.paymentId)).isOne()
    }

    @Test
    fun decline_returns_to_held_for_retry() {
        val paying = transitions.startPaying(accountId, hold(1))

        val settled = transitions.settle(accountId, paying, declined())

        assertThat(settled.reservationStatus).isEqualTo("held")
        assertThat(reservation(paying.reservationId)).isEqualTo("held")
        assertThat(seatStatuses(paying.reservationId)).containsOnly("held")
        assertThat(paymentRow(settled.paymentId)).isEqualTo("declined" to 154_000)
        // 다른 카드로 다시 — 선점 시간은 그대로다.
        assertThat(transitions.startPaying(accountId, paying.reservationId).amount).isEqualTo(154_000)
    }

    @Test
    fun decline_after_hold_expiry_expires_the_reservation() {
        val paying = transitions.startPaying(accountId, hold(1))
        jdbc.sql("update reservation set held_until = now() - interval '1 second' where reservation_id = :id").param("id", paying.reservationId).update()

        val settled = transitions.settle(accountId, paying, declined())

        // `paying → held` 는 선점 시간이 남았을 때만이다(`D3`). 지났으면 좌석을 돌려준다.
        assertThat(settled.reservationStatus).isEqualTo("expired")
        assertThat(freeSeatCount()).isEqualTo(4)
    }

    @Test
    fun no_response_is_recorded_as_failed_and_returns_to_held() {
        val paying = transitions.startPaying(accountId, hold(1))

        val settled = transitions.settle(accountId, paying, verdict = null)

        assertThat(paymentRow(settled.paymentId)).isEqualTo("failed" to 154_000)
        assertThat(settled.reservationStatus).isEqualTo("held")
    }

    @Test
    fun start_paying_needs_a_live_hold_of_mine() {
        val reservationId = hold(1)

        assertThatThrownBy { transitions.startPaying(fixture.account("stranger@test.local"), reservationId) }
            .isInstanceOf(TicketException::class.java).extracting("code").isEqualTo(ErrorCode.RESERVATION_NOT_FOUND)

        jdbc.sql("update reservation set held_until = now() - interval '1 second' where reservation_id = :id").param("id", reservationId).update()
        assertThatThrownBy { transitions.startPaying(accountId, reservationId) }
            .isInstanceOf(TicketException::class.java).extracting("code").isEqualTo(ErrorCode.HOLD_EXPIRED)

        jdbc.sql("update reservation set held_until = now() + interval '5 minutes' where reservation_id = :id").param("id", reservationId).update()
        fixture.reserve(reservationId)
        assertThatThrownBy { transitions.startPaying(accountId, reservationId) }
            .isInstanceOf(TicketException::class.java)
            .satisfies({ e ->
                e as TicketException
                assertThat(e.code).isEqualTo(ErrorCode.INVALID_TRANSITION)
                assertThat(e.properties).containsEntry("from", "reserved").containsEntry("action", "pay")
            })
    }

    @Test
    fun sweeper_does_not_touch_paying() {
        val paying = transitions.startPaying(accountId, hold(1))
        // 선점 시간은 지났지만 결제 중이다. 스윕이 여기를 풀면 승인이 T+ε 에 와서 돈은 받고 좌석은 없는 예매가 된다(ADR 0004).
        jdbc.sql("update reservation set held_until = now() - interval '1 second' where reservation_id = :id").param("id", paying.reservationId).update()

        assertThat(sweeper.sweep()).isZero()
        assertThat(reservation(paying.reservationId)).isEqualTo("paying")
    }

    @Test
    fun paying_timeout_expires_the_reservation() {
        val paying = transitions.startPaying(accountId, hold(1))
        jdbc.sql("update reservation set paying_until = now() - interval '1 second' where reservation_id = :id").param("id", paying.reservationId).update()

        assertThat(sweeper.sweep()).isEqualTo(1)
        assertThat(reservation(paying.reservationId)).isEqualTo("expired")
        assertThat(freeSeatCount()).isEqualTo(4)
    }

    @Test
    fun second_approval_for_one_reservation_is_rejected() {
        val paying = transitions.startPaying(accountId, hold(1))
        transitions.settle(accountId, paying, approved())

        // 멱등키가 앞에서 막고 이 인덱스가 끝에서 한 번 더 막는다(`V9`).
        assertThatThrownBy {
            jdbc.sql(
                """
                insert into payment (reservation_id, status, amount, approval_number, card_last4)
                values (:id, 'approved', 1, 'M-second', '1111')
                """,
            ).param("id", paying.reservationId).update()
        }.isInstanceOf(DuplicateKeyException::class.java).hasStackTraceContaining("payment_approved_idx")
    }

    @Test
    fun payment_row_cannot_be_updated() {
        val paying = transitions.startPaying(accountId, hold(1))
        val settled = transitions.settle(accountId, paying, approved())

        assertThatThrownBy {
            jdbc.sql("update payment set amount = 1 where payment_id = :id").param("id", settled.paymentId).update()
        }.hasStackTraceContaining("결제 기록은 고칠 수 없다")
    }

    private fun hold(seats: Int): Long = seatHold.hold(accountId, SeatHoldService.Command(performanceId, seatIds.take(seats)))

    private fun approved() = MockPaymentGateway.Result("M-${System.nanoTime()}", "4242", null)

    private fun declined() = MockPaymentGateway.Result(null, "0000", MockPaymentGateway.DECLINE_REASON)

    private fun reservation(reservationId: Long): String =
        jdbc.sql("select status from reservation where reservation_id = :id").param("id", reservationId).query(String::class.java).single()

    private fun seatStatuses(reservationId: Long): List<String> =
        jdbc.sql("select status from performance_seat where reservation_id = :id").param("id", reservationId)
            .query(String::class.java).list().filterNotNull()

    private fun freeSeatCount(): Long =
        jdbc.sql("select count(*) from performance_seat where performance_id = :id and status = 'available'")
            .param("id", performanceId).query(Long::class.java).single()

    private fun paymentRow(paymentId: Long): Pair<String, Int> =
        jdbc.sql("select status, amount from payment where payment_id = :id").param("id", paymentId)
            .query { rs, _ -> rs.getString("status") to rs.getInt("amount") }.single()

    private fun auditCount(eventType: String, targetId: Long): Long =
        jdbc.sql("select count(*) from audit_log where event_type = :type and target_id = :id")
            .param("type", eventType).param("id", targetId).query(Long::class.java).single()
}
