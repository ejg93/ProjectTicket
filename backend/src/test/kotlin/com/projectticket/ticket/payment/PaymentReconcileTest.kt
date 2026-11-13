package com.projectticket.ticket.payment

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.reservation.HoldSweeper
import com.projectticket.ticket.reservation.SeatHoldService
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * PG 가 승인했는데 결과 반영(③)이 롤백되면 대사가 그 승인을 `late` 로 다시 적나(`I17-1`, `D4` 「결제」).
 *
 * 「③ 이 죽었다」는 `startPaying` + PG `approve` 만 하고 `settle` 을 안 부르는 것으로 만든다 — 롤백된 것과 DB 에 남는 모양이 같다.
 * 커밋 레인이다: 대사가 행마다 제 트랜잭션으로 `settle` 을 부르고, 스윕·환불 큐가 그 커밋을 읽는다.
 */
class PaymentReconcileTest : ConcurrencyTestBase() {

    @Autowired lateinit var gateway: MockPaymentGateway
    @Autowired lateinit var payments: PaymentTransitionService
    @Autowired lateinit var sweeper: HoldSweeper
    @Autowired lateinit var reconciler: PaymentReconciler
    @Autowired lateinit var refunds: RefundTransitionService
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var seatHold: SeatHoldService

    @Test
    fun an_approval_whose_settle_died_is_recorded_late_and_refunded() {
        val paying = approvedButNeverSettled()
        expire(paying.reservationId)

        reconciler.reconcile()

        val paymentId = approvedPaymentOf(paying.reservationId)
        assertThat(paymentId).describedAs("PG 는 돈을 받았는데 우리 표에 결제가 없으면 환불도 없다").isNotNull()
        assertThat(auditCount("payment.late", checkNotNull(paymentId))).isOne()
        assertThat(attemptSettled(paying.gatewayKey)).isTrue()

        refunds.queueLatePayments()
        assertThat(refundCount(paymentId)).describedAs("late 승인은 전액 환불 행이 선다").isOne()
    }

    @Test
    fun a_key_the_gateway_never_saw_only_closes_the_attempt() {
        val (accountId, reservationId) = held()
        val paying = payments.startPaying(accountId, reservationId, UUID.randomUUID().toString())
        expire(reservationId)

        reconciler.reconcile()

        assertThat(attemptSettled(paying.gatewayKey)).isTrue()
        assertThat(paymentCount(reservationId)).isZero()
    }

    @Test
    fun reconciling_twice_records_one_payment() {
        val paying = approvedButNeverSettled()
        expire(paying.reservationId)

        reconciler.reconcile()
        reconciler.reconcile()

        assertThat(paymentCount(paying.reservationId)).isOne()
    }

    @Test
    fun a_paying_reservation_is_left_to_its_own_request() {
        val paying = approvedButNeverSettled()

        reconciler.reconcile()

        // 원 요청이 아직 PG 를 기다리는 중일 수 있다 — 기한이 지나 스윕이 `expired` 로 옮긴 다음 회가 잡는다.
        assertThat(attemptSettled(paying.gatewayKey)).isFalse()
        assertThat(paymentCount(paying.reservationId)).isZero()
    }

    @Test
    fun settle_closes_the_attempt_in_the_same_transaction() {
        val (accountId, reservationId) = held()
        val paying = payments.startPaying(accountId, reservationId, UUID.randomUUID().toString())

        payments.settle(accountId, paying, gateway.approve(MockPaymentGateway.Request(paying.gatewayKey, paying.amount, CARD)))

        assertThat(attemptSettled(paying.gatewayKey)).isTrue()
    }

    @Test
    fun a_confirm_that_finds_a_released_seat_rolls_back_and_is_reconciled_late() {
        val (accountId, reservationId) = held()
        val paying = payments.startPaying(accountId, reservationId, UUID.randomUUID().toString())
        // 좌석이 `reserved` 예매 밖으로 풀린 모양 — 생 SQL·버그. 이대로 확정하면 「예매는 reserved, 좌석은 available」이다(`I17-8`).
        jdbc.sql("update performance_seat set status = 'available', held_until = null, reservation_id = null where reservation_id = :id")
            .param("id", reservationId)
            .update()
        val verdict = gateway.approve(MockPaymentGateway.Request(paying.gatewayKey, paying.amount, CARD))

        assertThatThrownBy { payments.settle(accountId, paying, verdict) }.isInstanceOf(IllegalStateException::class.java)

        // 결과 반영이 통째로 롤백됐다 — 결제 행이 없고 예매는 `paying` 이다.
        assertThat(paymentCount(reservationId)).isZero()
        assertThat(statusOf(reservationId)).isEqualTo("paying")
        // 기한이 지나 만료되면 대사가 그 승인을 `late` 로 적는다 — 환불로 잇는다(`I17-1`).
        expire(reservationId)
        reconciler.reconcile()
        assertThat(approvedPaymentOf(reservationId)).isNotNull()
    }

    private fun statusOf(reservationId: Long): String =
        jdbc.sql("select status from reservation where reservation_id = :id").param("id", reservationId).query(String::class.java).single()

    private fun approvedButNeverSettled(): PaymentTransitionService.Paying {
        val (accountId, reservationId) = held()
        val paying = payments.startPaying(accountId, reservationId, UUID.randomUUID().toString())
        assertThat(paying.gatewayKey).startsWith("$reservationId:")
        gateway.approve(MockPaymentGateway.Request(paying.gatewayKey, paying.amount, CARD))
        return paying
    }

    /** `paying_until` 을 지난 시각으로 옮기고 스윕을 돌린다 — 「결제 기한이 지나 만료됐다」 */
    private fun expire(reservationId: Long) {
        jdbc.sql("update reservation set paying_until = now() - interval '1 second' where reservation_id = :id and status = 'paying'")
            .param("id", reservationId)
            .update()
        sweeper.sweep()
        assertThat(
            jdbc.sql("select status from reservation where reservation_id = :id").param("id", reservationId).query(String::class.java).single(),
        ).isEqualTo("expired")
    }

    private fun held(): Pair<Long, Long> {
        val fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("${PREFIX}reconcile"))
        val hallId = fixture.hall(venueName = "${PREFIX}reconcile-${System.nanoTime()}")
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        val performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)

        val accountId = fixture.account("${PREFIX}reconcile-${System.nanoTime()}@test.local")
        val seatId = fixture.performanceSeatIds(performanceId).first()
        return accountId to seatHold.hold(accountId, SeatHoldService.Command(performanceId, listOf(seatId)))
    }

    private fun attemptSettled(gatewayKey: String): Boolean =
        jdbc.sql("select settled_at is not null from payment_attempt where gateway_key = :key")
            .param("key", gatewayKey)
            .query(Boolean::class.java)
            .single()

    private fun approvedPaymentOf(reservationId: Long): Long? =
        jdbc.sql("select payment_id from payment where reservation_id = :id and status = 'approved'")
            .param("id", reservationId)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    private fun paymentCount(reservationId: Long): Long =
        jdbc.sql("select count(*) from payment where reservation_id = :id").param("id", reservationId).query(Long::class.java).single()

    private fun refundCount(paymentId: Long): Long =
        jdbc.sql("select count(*) from refund where payment_id = :id").param("id", paymentId).query(Long::class.java).single()

    private fun auditCount(eventType: String, targetId: Long): Long =
        jdbc.sql("select count(*) from audit_log where event_type = :type and target_id = :id")
            .param("type", eventType)
            .param("id", targetId)
            .query(Long::class.java)
            .single()

    private companion object {
        const val CARD = "4242424242424242"
    }
}
