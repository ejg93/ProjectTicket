package com.projectticket.ticket.payment

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.reservation.SeatHoldService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 환불 등식(`D6`) `fee + refund = payment.amount` 이 커밋 때 실제로 걸리는가. 지연 제약 트리거라 커밋 레인이다(`stack.md`).
 */
class RefundInvariantTest : ConcurrencyTestBase() {

    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var payments: PaymentTransitionService
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun refund_that_does_not_add_up_cannot_commit() {
        val fixture = EventFixture(jdbc)
        val accountId = fixture.account("${PREFIX}refund@test.local")
        val eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        val hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 1)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        val performanceId = fixture.performance(eventId, hallId, startsInDays = 8).also { openService.open(it, actorAccountId = null) }
        val reservationId = seatHold.hold(accountId, SeatHoldService.Command(performanceId, fixture.performanceSeatIds(performanceId)))
        val paying = payments.startPaying(accountId, reservationId)
        val settled = payments.settle(accountId, paying, MockPaymentGateway.Result("M-${System.nanoTime()}", "4242", null))

        // 154,000 을 받고 15,400 + 100,000 을 적으면 38,600 원이 어디로 갔는지 아무도 모른다.
        assertThatThrownBy {
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbc.sql(
                    """
                    insert into refund (payment_id, reason, days_before, tier_rate, fee_amount, refund_amount)
                    values (:payment, 'audience', 8, 0.10, 15400, 100000)
                    """,
                ).param("payment", settled.paymentId).update()
            }
        }.hasStackTraceContaining("환불 등식이 안 맞는다")
    }
}
