package com.projectticket.ticket.payment

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.reservation.SeatHoldService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
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

    @Autowired lateinit var refunds: RefundTransitionService

    private lateinit var fixture: EventFixture
    private var accountId: Long = 0
    private var eventId: Long = 0
    private var hallId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        accountId = fixture.account("${PREFIX}refund@test.local")
        eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 1)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
    }

    /** 결제까지 마친 예매. 관람일은 `now() + startsInDays` */
    private fun reserved(startsInDays: Long): Pair<Long, Long> {
        val performanceId = fixture.performance(eventId, hallId, startsInDays).also { openService.open(it, actorAccountId = null) }
        val reservationId = seatHold.hold(accountId, SeatHoldService.Command(performanceId, fixture.performanceSeatIds(performanceId)))
        val paying = payments.startPaying(accountId, reservationId)
        return reservationId to payments.settle(accountId, paying, MockPaymentGateway.Result("M-${System.nanoTime()}", "4242", null)).paymentId
    }

    /**
     * 당일 취소는 아무것도 안 움직인다 — 조건부 UPDATE 가 먼저 돌고 구간이 없어 던지면 트랜잭션이 통째로 되돌아간다.
     * 롤백 바탕에서는 못 본다(`stack.md` — rollback-only 표시만 남는다).
     */
    @Test
    fun same_day_cancel_moves_nothing() {
        val (reservationId, _) = reserved(startsInDays = 0)

        assertThatThrownBy { refunds.request(accountId, reservationId) }.hasMessageContaining("당일")

        assertNothingMoved(reservationId)
    }

    /**
     * 본 금액이 지금 계산과 다르면(`44a-1a`) 당일 취소처럼 아무것도 안 움직인다 — 같은 자리(조건부 UPDATE 뒤)에서 던진다.
     * 구간표를 개정하는 대신 틀린 금액을 싣는다 — 커밋 레인에서 새 판을 넣으면 뒤 시험 전부가 그 판을 본다.
     */
    @Test
    fun a_stale_amount_moves_nothing() {
        val (reservationId, _) = reserved(startsInDays = 8)

        assertThatThrownBy { refunds.request(accountId, reservationId, expectedRefund = 1) }.hasMessageContaining("본 금액")

        assertNothingMoved(reservationId)
    }

    private fun assertNothingMoved(reservationId: Long) {
        assertThat(jdbc.sql("select status from reservation where reservation_id = :id").param("id", reservationId).query(String::class.java).single())
            .isEqualTo("reserved")
        assertThat(jdbc.sql("select count(*) from performance_seat where reservation_id = :id and status = 'reserved'").param("id", reservationId).query(Long::class.java).single())
            .isOne()
        assertThat(jdbc.sql("select count(*) from refund r join payment p on p.payment_id = r.payment_id where p.reservation_id = :id").param("id", reservationId).query(Long::class.java).single())
            .isZero()
    }

    @Test
    fun refund_that_does_not_add_up_cannot_commit() {
        val (_, paymentId) = reserved(startsInDays = 8)

        // 154,000 을 받고 15,400 + 100,000 을 적으면 38,600 원이 어디로 갔는지 아무도 모른다.
        assertThatThrownBy {
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbc.sql(
                    """
                    insert into refund (payment_id, reason, days_before, tier_rate, fee_amount, refund_amount)
                    values (:payment, 'audience', 8, 0.10, 15400, 100000)
                    """,
                ).param("payment", paymentId).update()
            }
        }.hasStackTraceContaining("환불 등식이 안 맞는다")
    }
}
