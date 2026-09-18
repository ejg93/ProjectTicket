package com.projectticket.ticket.payment

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 승인이 늦어 좌석을 못 준 결제에 환불 행이 생기나(`17b` ⓑ의 닫힘 조건).
 *
 * 16 은 PG 취소만 보내고 행을 안 만들었다 — **돈은 돌아갔는데 우리 표에는 그 사실이 없다.**
 * 정산(27)과 조회가 「승인된 결제인데 예매가 없다」를 설명하지 못하는 자리고, 그 침묵은 아무 오류도 안 낸다.
 */
class RefundSweeperTest : PostgresTestBase() {

    @Autowired lateinit var sweeper: RefundSweeper
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var jdbc: JdbcClient

    private lateinit var fixture: EventFixture
    private var performanceId: Long = 0
    private var buyer: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        val hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
        buyer = fixture.account("${PREFIX}buyer@test.local")
    }

    @Test
    fun a_late_approval_gets_a_full_refund_row() {
        val reservationId = expiredHoldWithApprovedPayment(seatNumber = 1)

        assertThat(sweeper.sweep()).isEqualTo(1)

        val refund = refundOf(reservationId)
        // 관객 잘못이 아니다 — 율 0, 수수료 0, 전액(`D6`).
        assertThat(refund.reason).isEqualTo("payment_late")
        assertThat(refund.feeAmount).isZero()
        assertThat(refund.refundAmount).isEqualTo(154_000)
        // 행만 만들고 끝나면 돈이 안 돌아간다. 같은 회에 PG 까지 보낸다.
        assertThat(refund.status).isEqualTo("done")
    }

    @Test
    fun sweeping_twice_makes_one_row() {
        expiredHoldWithApprovedPayment(seatNumber = 1)

        assertThat(sweeper.sweep()).isEqualTo(1)
        assertThat(sweeper.sweep()).isZero()
        assertThat(refundCount()).isEqualTo(1)
    }

    @Test
    fun a_live_reservation_is_left_alone() {
        // 예매가 살아 있으면 좌석을 받은 것이다. 여기에 환불 행을 만들면 산 사람에게 돈을 돌려준다.
        val reservationId = fixture.hold(buyer, performanceId, "F1-A", 1)
        approve(reservationId)
        fixture.reserve(reservationId)

        assertThat(sweeper.sweep()).isZero()
        assertThat(refundCount()).isZero()
    }

    /** 선점이 만료된 뒤에 승인이 도착한 모양. 16 이 PG 취소만 보내고 행을 안 남긴 그 상태다 */
    private fun expiredHoldWithApprovedPayment(seatNumber: Int): Long {
        val reservationId = fixture.hold(buyer, performanceId, "F1-A", seatNumber)
        approve(reservationId)
        jdbc.sql(
            "update reservation set status = 'expired', expired_at = now(), cancelled_by = 'expired' where reservation_id = :id",
        ).param("id", reservationId).update()
        return reservationId
    }

    private fun approve(reservationId: Long) {
        jdbc.sql(
            """
            insert into payment (reservation_id, status, amount, approval_number, card_last4)
            select :id, 'approved', total_amount, 'late-' || :id, '4242' from reservation where reservation_id = :id
            """,
        ).param("id", reservationId).update()
    }

    private fun refundOf(reservationId: Long): Row =
        jdbc.sql(
            """
            select rf.reason, rf.fee_amount, rf.refund_amount, rf.status
              from refund rf join payment p on p.payment_id = rf.payment_id
             where p.reservation_id = :id
            """,
        ).param("id", reservationId).query(Row::class.java).single()

    private fun refundCount(): Long =
        jdbc.sql("select count(*) from refund").query(Long::class.java).single()

    data class Row(val reason: String, val feeAmount: Int, val refundAmount: Int, val status: String)

    private companion object {
        const val PREFIX = "late-refund-"
    }
}
