package com.projectticket.ticket.settlement

import tools.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.outbox.PendingEvents
import com.projectticket.ticket.payment.MockPaymentGateway
import com.projectticket.ticket.payment.PaymentTransitionService
import com.projectticket.ticket.payment.RefundTransitionService
import com.projectticket.ticket.reservation.PerformanceCloser
import com.projectticket.ticket.reservation.SeatHoldService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 둘째 소비자가 `D21` 대로 도는가 — 회차가 닫히면 예약되고, `settle_at` 뒤에 **항목의 합**으로 정산서가 선다.
 *
 * **커밋 레인이다.** 소비자가 `REQUIRES_NEW` 로 돌고(릴레이를 안 멈추려고) 합계가 지연 트리거라, 롤백 레인에서는 둘 다 한 번도 안 돈다(`stack.md`).
 * 스케줄러는 꺼져 있다 — 릴레이·집계 회차를 손으로 부른다.
 */
class SettlementAggregateTest : ConcurrencyTestBase() {

    @Autowired lateinit var jdbcForEvents: JdbcClient
    @Autowired lateinit var jsonForEvents: ObjectMapper
    @Autowired lateinit var settlementStore: SettlementStore
    @Autowired lateinit var closer: PerformanceCloser
    @Autowired lateinit var sweeper: SettlementSweeper
    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var payments: PaymentTransitionService
    @Autowired lateinit var refunds: RefundTransitionService
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var cancelService: com.projectticket.ticket.payment.PerformanceCancelService
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    private lateinit var fixture: EventFixture
    private var eventId: Long = 0
    private var hallId: Long = 0
    private var performanceId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 4)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId, startsInDays = 8).also { openService.open(it, actorAccountId = null) }
    }

    @Test
    fun opening_freezes_the_policy() {
        // 요율을 나중에 고쳐도 이미 열린 회차의 정산이 안 흔들린다(`D21`·`D7`).
        assertThat(
            jdbc.sql("select sp.commission_rate from performance p join settlement_policy sp on sp.settlement_policy_id = p.settlement_policy_id where p.performance_id = :id")
                .param("id", performanceId).query(java.math.BigDecimal::class.java).single(),
        ).isEqualByComparingTo("0.10")
    }

    @Test
    fun closing_schedules_a_settlement_at_the_policy_delay() {
        closeWithEvent()

        val row = settlementRow()
        assertThat(row.status).isEqualTo("scheduled")
        assertThat(row.amount).isZero()
        // D+7 이 박제된다 — 정책을 고쳐도 예약된 정산이 안 흔들린다.
        assertThat(daysUntilSettle()).isEqualTo(7)
        assertThat(lineCount()).describedAs("예약 시점에는 항목이 없다").isZero()
    }

    @Test
    fun the_same_event_twice_still_makes_one_settlement() {
        closeWithEvent()
        // 사건이 다시 나가도(at-least-once) 회차당 유일 제약이 둘째를 막는다 — 예약 표를 따로 안 둔 이유다.
        jdbc.sql("update outbox set published_at = null where type = 'performance.closed' and aggregate_id = :id").param("id", performanceId).update()
        deliver()

        assertThat(settlementCount()).isOne()
    }

    @Test
    fun sale_equals_reserved_seat_prices() {
        val buyer = fixture.account("${PREFIX}buyer@test.local")
        reserve(buyer, seats = 2)
        // 선점만 하고 결제 안 한 예매는 회차가 닫힐 때 만료된다 — 매출에 안 든다.
        seatHold.hold(fixture.account("${PREFIX}holder@test.local"), SeatHoldService.Command(performanceId, seatIds().drop(2).take(1)))
        closeWithEvent()

        settleNow()

        assertThat(lineOf("sale")).isEqualTo(308_000)
        assertThat(lineOf("platform_fee")).isEqualTo(-30_800)
        assertThat(lineOf("cancel_fee")).isZero()
        // 정산서 금액은 항목의 합이다. 어긋나면 지연 트리거가 커밋 때 막는다.
        assertThat(settlementRow().amount).isEqualTo(277_200)
        assertThat(settlementRow().status).isEqualTo("pending")
    }

    @Test
    fun cancel_fee_goes_to_the_organizer() {
        val buyer = fixture.account("${PREFIX}buyer@test.local")
        val kept = reserve(buyer, seats = 1)
        val cancelled = reserve(fixture.account("${PREFIX}canceller@test.local"), seats = 1, from = 1)
        refunds.request(accountOf(cancelled), cancelled)
        closeWithEvent()

        settleNow()

        // 관람일 8일 전 취소 → 10% 수수료 15,400. 기본 정책은 기획사 전부(`cancel_fee_share = 1.00`).
        assertThat(lineOf("sale")).describedAs("취소된 예매는 매출에 안 든다").isEqualTo(154_000)
        assertThat(lineOf("cancel_fee")).isEqualTo(15_400)
        assertThat(settlementRow().amount).isEqualTo(154_000 - 15_400 + 15_400)
        assertThat(kept).isPositive()
    }

    @Test
    fun cancelled_performance_settles_to_zero() {
        reserve(fixture.account("${PREFIX}buyer@test.local"), seats = 2)
        cancelService.cancel(performanceId, actorAccountId = null)
        deliver()

        settleNow()

        // 취소된 회차도 정산서가 선다 — 항목이 전부 0 이고 「이 회차는 취소돼서 0 이다」가 기록으로 남는다(`D21` 「회차 취소」).
        // 분기가 없다: 매출은 `reserved` 만 세고 취소 수수료는 `audience` 만 세므로 계산이 저절로 0 이다.
        assertThat(settlementRow().amount).isZero()
        assertThat(lineOf("sale")).isZero()
        assertThat(lineOf("cancel_fee")).isZero()
    }

    @Test
    fun a_performance_with_nothing_sold_settles_to_zero() {
        closeWithEvent()

        settleNow()

        // 0원 정산서도 만든다 — 「이 회차는 0 이다」가 기록으로 남고 회차당 한 장이라는 규칙이 지켜진다(`D21`).
        assertThat(settlementRow().amount).isZero()
        assertThat(settlementRow().status).isEqualTo("pending")
        assertThat(lineCount()).isEqualTo(3)
    }

    @Test
    fun a_settlement_before_its_due_date_is_untouched() {
        closeWithEvent()

        assertThat(sweeper.sweep()).describedAs("D+7 이 안 지났다").isZero()
        assertThat(settlementRow().status).isEqualTo("scheduled")
    }

    @Test
    fun settling_twice_does_not_double_the_lines() {
        closeWithEvent()
        settleNow()

        // 이미 집계된 것은 부분 인덱스에서 빠진다. 두 번째 회가 0 이 아니면 같은 회차가 두 번 정산된다.
        assertThat(sweeper.sweep()).isZero()
        assertThat(lineCount()).isEqualTo(3)
    }

    @Test
    fun a_total_that_differs_from_the_lines_cannot_commit() {
        closeWithEvent()
        settleNow()

        // 항목을 하나 더하면 합이 어긋난다 — 정산서가 실제와 다른 돈을 말하는 자리다.
        assertThatThrownBy {
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbc.sql("insert into settlement_line (settlement_id, kind, amount) values (:id, 'adjustment', 1000)")
                    .param("id", settlementRow().settlementId).update()
            }
        }.hasStackTraceContaining("정산서 금액이 항목의 합과 다르다")
    }

    /** 회차를 닫고 그 사건을 발행한다 — 소비자가 예약을 만든다 */
    private fun closeWithEvent() {
        jdbc.sql("update performance set sales_close_at = now() - interval '1 second' where performance_id = :id").param("id", performanceId).update()
        closer.closeDue()
        deliver()
    }

    /** `settle_at` 을 지난 시각으로 당기고 집계한다 — 시계가 DB 라 기다릴 방법이 없다(`D8`) */
    private fun settleNow() {
        jdbc.sql("update settlement set settle_at = now() - interval '1 second' where performance_id = :id").param("id", performanceId).update()
        sweeper.sweep()
    }

    private fun reserve(accountId: Long, seats: Int, from: Int = 0): Long {
        val reservationId = seatHold.hold(accountId, SeatHoldService.Command(performanceId, seatIds().drop(from).take(seats)))
        val paying = payments.startPaying(accountId, reservationId)
        payments.settle(accountId, paying, MockPaymentGateway.Result("M-${System.nanoTime()}", "4242", null))
        return reservationId
    }

    private fun seatIds(): List<Long> = fixture.performanceSeatIds(performanceId)

    private fun accountOf(reservationId: Long): Long =
        jdbc.sql("select account_id from reservation where reservation_id = :id").param("id", reservationId).query(Long::class.java).single()

    private fun settlementRow(): Row =
        jdbc.sql("select settlement_id, amount, status from settlement where performance_id = :id")
            .param("id", performanceId).query(Row::class.java).single()

    private fun settlementCount(): Long =
        jdbc.sql("select count(*) from settlement where performance_id = :id").param("id", performanceId).query(Long::class.java).single()

    private fun daysUntilSettle(): Int =
        jdbc.sql("select (settle_at::date - now()::date) from settlement where performance_id = :id")
            .param("id", performanceId).query(Int::class.java).single()

    private fun lineOf(kind: String): Int =
        jdbc.sql("select l.amount from settlement_line l join settlement s on s.settlement_id = l.settlement_id where s.performance_id = :id and l.kind = :kind")
            .param("id", performanceId).param("kind", kind).query(Int::class.java).single()

    private fun lineCount(): Long =
        jdbc.sql("select count(*) from settlement_line l join settlement s on s.settlement_id = l.settlement_id where s.performance_id = :id")
            .param("id", performanceId).query(Long::class.java).single()

    data class Row(val settlementId: Long, val amount: Int, val status: String)

    /**
     * 안 나간 사건을 **브로커를 건너뛰고** 소비자에게 바로 건넨다(28).
     * 롤백 레인이라 진짜 Kafka 소비자는 이 트랜잭션의 행을 못 본다 — 배선은 `KafkaRelayTest` 가 잰다.
     */
    private fun deliver(): Int = PendingEvents(jdbcForEvents, jsonForEvents).deliver(settlementStore::schedule)
}
