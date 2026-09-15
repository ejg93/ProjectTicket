package com.projectticket.ticket.outbox

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.payment.MockPaymentGateway
import com.projectticket.ticket.payment.PaymentTransitionService
import com.projectticket.ticket.payment.RefundTransitionService
import com.projectticket.ticket.reservation.SeatHoldService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

/**
 * **확정 없이 사건 없고, 사건 없이 확정 없다**(`D11` 「발행」). Transactional Outbox 가 사는 이유가 이 한 줄이다.
 *
 * **커밋 레인이다.** 롤백이 사건도 되돌리는지는 커밋이 실제로 있어야 보인다 — 롤백 바탕에서는 서비스의 롤백이 rollback-only 표시만 남긴다(`stack.md`).
 * 스케줄러는 꺼져 있다(`SchedulingConfig`) — 릴레이 회차를 손으로 부른다. 여기서 재는 것은 **행이 있나 없나**다.
 */
class OutboxAtomicityTest : ConcurrencyTestBase() {

    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var payments: PaymentTransitionService
    @Autowired lateinit var refunds: RefundTransitionService
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var outbox: OutboxWriter
    @Autowired lateinit var json: ObjectMapper

    private lateinit var fixture: EventFixture
    private var accountId: Long = 0
    private var eventId: Long = 0
    private var hallId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        accountId = fixture.account("${PREFIX}outbox@test.local")
        eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
    }

    @Test
    fun confirmation_commits_the_event_with_it() {
        val (reservationId, paymentId) = reserved(startsInDays = 8)

        val row = eventOf(reservationId, "reservation.reserved")
        assertThat(row.aggregateType).isEqualTo("reservation")
        assertThat(row.version).isEqualTo(1)
        // 카탈로그가 정한 필드 그대로(`D11`). 빠지면 소비자가 표를 못 찾는다.
        assertThat(payloadOf(row)).containsOnlyKeys(
            "reservation_id", "account_id", "performance_id", "payment_id", "total_amount", "seat_count",
        )
        assertThat(payloadOf(row)["payment_id"]).isEqualTo(paymentId.toInt())
        assertThat(payloadOf(row)["total_amount"]).isEqualTo(308_000)
        assertThat(payloadOf(row)["seat_count"]).isEqualTo(2)
        // 개인정보를 안 담는다(`D11`·`D10`) — 사건은 브로커·DLQ·로그에 남는다.
        assertThat(json.writeValueAsString(payloadOf(row))).doesNotContain("@test.local")
    }

    @Test
    fun cancellation_commits_its_own_event() {
        val (reservationId, _) = reserved(startsInDays = 8)

        refunds.request(accountId, reservationId)

        val payload = payloadOf(eventOf(reservationId, "reservation.cancelled"))
        assertThat(payload).containsOnlyKeys(
            "reservation_id", "account_id", "performance_id", "refund_id", "reason", "fee_amount", "refund_amount",
        )
        assertThat(payload["reason"]).isEqualTo("audience")
        assertThat(payload["fee_amount"]).isEqualTo(30_800)
    }

    @Test
    fun rolled_back_transaction_leaves_no_event() {
        // 당일 취소는 조건부 UPDATE 가 돈 뒤 예외로 끝난다 — 사건 행도 같이 사라져야 한다.
        val (reservationId, _) = reserved(startsInDays = 0)

        assertThatThrownBy { refunds.request(accountId, reservationId) }.hasMessageContaining("당일")

        assertThat(eventCount(reservationId, "reservation.cancelled")).isZero()
        assertThat(eventCount(reservationId, "reservation.reserved")).describedAs("확정 사건은 그대로다").isOne()
    }

    @Test
    fun late_approval_makes_no_event() {
        val reservationId = hold(startsInDays = 8)
        val paying = payments.startPaying(accountId, reservationId)
        jdbc.sql("update reservation set paying_until = now() - interval '1 second' where reservation_id = :id").param("id", reservationId).update()

        val settled = payments.settle(accountId, paying, MockPaymentGateway.Result("M-${System.nanoTime()}", "4242", null))

        // 돈은 받았고 좌석은 못 줬다 — 예매가 성립 안 했으니 확정 사건도 없다. 되돌리는 것은 환불(17b)이다.
        assertThat(settled.late).isTrue()
        assertThat(eventCount(reservationId, "reservation.reserved")).isZero()
    }

    @Test
    fun writing_an_event_outside_a_transaction_is_rejected() {
        // `MANDATORY` 가 강제 지점이다. 트랜잭션 밖에서 넣으면 사건만 남은 행이 생기고, 그 소비자는 없는 예매에 메일을 보낸다.
        assertThatThrownBy { outbox.append(EventType.RESERVATION_RESERVED, 1, mapOf("reservation_id" to 1)) }
            .hasMessageContaining("No existing transaction found")
    }

    private fun hold(startsInDays: Long): Long {
        val performanceId = fixture.performance(eventId, hallId, startsInDays).also { openService.open(it, actorAccountId = null) }
        return seatHold.hold(accountId, SeatHoldService.Command(performanceId, fixture.performanceSeatIds(performanceId)))
    }

    /** 2석 308,000원을 결제까지 마친 예매 */
    private fun reserved(startsInDays: Long): Pair<Long, Long> {
        val reservationId = hold(startsInDays)
        val paying = payments.startPaying(accountId, reservationId)
        return reservationId to payments.settle(accountId, paying, MockPaymentGateway.Result("M-${System.nanoTime()}", "4242", null)).paymentId
    }

    private fun eventOf(reservationId: Long, type: String): Row =
        jdbc.sql(
            "select type, version, aggregate_type, payload::text as payload from outbox where type = :type and aggregate_id = :id",
        ).param("type", type).param("id", reservationId).query(Row::class.java).single()

    private fun eventCount(reservationId: Long, type: String): Long =
        jdbc.sql("select count(*) from outbox where type = :type and aggregate_id = :id")
            .param("type", type).param("id", reservationId).query(Long::class.java).single()

    @Suppress("UNCHECKED_CAST")
    private fun payloadOf(row: Row): Map<String, Any?> = json.readValue(row.payload, Map::class.java) as Map<String, Any?>

    data class Row(val type: String, val version: Int, val aggregateType: String, val payload: String)
}
