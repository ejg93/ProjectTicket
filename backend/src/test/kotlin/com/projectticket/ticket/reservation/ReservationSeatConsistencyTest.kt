package com.projectticket.ticket.reservation

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.payment.MockPaymentGateway
import com.projectticket.ticket.payment.PaymentTransitionService
import com.projectticket.ticket.payment.RefundTransitionService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 예매 두 표의 불변식(`D4` 「두 표의 역할」·「합계 불변식」)이 실제로 걸리는가.
 *
 * **롤백 없는 바탕이다.** 합계·응답 검사가 지연 제약 트리거라 커밋 시점에만 돌고, 롤백하는 테스트에서는 한 번도 안 돈다(`stack.md`).
 * SQL 을 직접 던진다 — 서비스를 거치면 앱이 먼저 막아서 제약이 도는지를 못 본다(`D14` 축 2).
 */
class ReservationSeatConsistencyTest : ConcurrencyTestBase() {

    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var payments: PaymentTransitionService
    @Autowired lateinit var refunds: RefundTransitionService
    @Autowired lateinit var sweeper: HoldSweeper
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    private lateinit var fixture: EventFixture
    private var accountId: Long = 0
    private var performanceId: Long = 0
    private var seatIds: List<Long> = emptyList()

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        accountId = fixture.account("${PREFIX}buyer@test.local")
        val eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        val hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 3)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        // 관람일이 넉넉해야 취소가 구간표에 걸린다(당일이면 409).
        performanceId = fixture.performance(eventId, hallId, startsInDays = 8)
        openService.open(performanceId, actorAccountId = null)
        seatIds = fixture.performanceSeatIds(performanceId)
    }

    @Test
    fun held_reservation_seats_equal_the_pointer_seats() {
        val reservationId = seatHold.hold(accountId, SeatHoldService.Command(performanceId, seatIds.take(2)))

        val recorded = jdbc.sql("select performance_seat_id from reservation_seat where reservation_id = :id")
            .param("id", reservationId).query(Long::class.java).list().filterNotNull().toSet()
        val pointed = jdbc.sql("select performance_seat_id from performance_seat where reservation_id = :id and status = 'held'")
            .param("id", reservationId).query(Long::class.java).list().filterNotNull().toSet()

        // 기록과 포인터가 갈리면 만료된 예매가 무엇을 잡았었는지와 지금 누가 쥐고 있는지가 다른 답을 낸다.
        assertThat(recorded).isEqualTo(seatIds.take(2).toSet())
        assertThat(pointed).isEqualTo(recorded)
        assertThat(totalOf(reservationId)).isEqualTo(154_000 * 2)
    }

    /**
     * `D4` 「두 표의 역할」 — 살아있는 예매(`held`·`paying`·`reserved`)에서 기록 집합 = 포인터 집합이고, 끝난 예매에서 포인터는 비고 기록은 남는다.
     * **매 전이 뒤에** 본다. 전이 하나가 두 표를 고치는 자리 전부가 갈릴 수 있는 자리다.
     */
    @Test
    fun record_and_pointer_sets_agree_after_every_transition() {
        val reservationId = seatHold.hold(accountId, SeatHoldService.Command(performanceId, seatIds.take(2)))
        val expected = seatIds.take(2).toSet()
        assertThat(pointerSet(reservationId)).describedAs("held").isEqualTo(expected)

        val paying = payments.startPaying(accountId, reservationId)
        assertThat(pointerSet(reservationId)).describedAs("paying").isEqualTo(expected)

        payments.settle(accountId, paying, MockPaymentGateway.Result("M-${System.nanoTime()}", "4242", null))
        assertThat(pointerSet(reservationId)).describedAs("reserved").isEqualTo(expected)

        refunds.request(accountId, reservationId)
        assertThat(pointerSet(reservationId)).describedAs("cancelled — 포인터는 풀린다").isEmpty()
        assertThat(recordSet(reservationId)).describedAs("cancelled — 기록은 남는다").isEqualTo(expected)
    }

    @Test
    fun expired_reservation_keeps_its_record_but_no_pointer() {
        val reservationId = seatHold.hold(accountId, SeatHoldService.Command(performanceId, seatIds.take(1)))
        jdbc.sql("update reservation set held_until = now() - interval '1 second' where reservation_id = :id").param("id", reservationId).update()

        sweeper.sweep()

        assertThat(pointerSet(reservationId)).isEmpty()
        assertThat(recordSet(reservationId)).isEqualTo(seatIds.take(1).toSet())
    }

    /**
     * 한 석이라도 못 잡으면 전부 안 잡는다(ADR 0003) — 예매 행도, 잡혔던 다른 좌석도 안 남는다.
     * 롤백 바탕에서는 이것을 못 본다: 서비스의 예외가 테스트 트랜잭션에 rollback-only 표시만 남기고 행은 그대로 보인다(`stack.md`).
     */
    @Test
    fun losing_hold_leaves_nothing_behind() {
        fixture.hold(fixture.account("${PREFIX}other@test.local"), performanceId, "F1-A", 1)

        assertThatThrownBy { seatHold.hold(accountId, SeatHoldService.Command(performanceId, seatIds.take(2))) }
            .hasMessageContaining("이미 잡혔다")

        assertThat(jdbc.sql("select count(*) from reservation where account_id = :id").param("id", accountId).query(Long::class.java).single())
            .describedAs("진 쪽의 예매 행이 남으면 예매 행 삽입과 조건부 UPDATE 가 한 트랜잭션이 아닌 것이다").isZero()
        assertThat(jdbc.sql("select status from performance_seat where performance_seat_id = :id").param("id", seatIds[1]).query(String::class.java).single())
            .describedAs("4석 중 3석만 잡히는 「가능한 것만」은 없다").isEqualTo("available")
    }

    @Test
    fun total_that_differs_from_seat_prices_cannot_commit() {
        // 합계는 원본과 어긋날 수 있는 유일한 값이다. 어긋난 채 커밋되면 정산(27)이 틀린 돈을 센다.
        assertThatThrownBy {
            inTransaction {
                val reservationId = insertReservation(totalAmount = 1)
                insertSeatRecord(reservationId, seatIds[0], 154_000)
            }
        }.hasStackTraceContaining("예매 합계가 좌석 가격의 합과 다르다")
    }

    @Test
    fun live_reservation_without_seats_cannot_commit() {
        assertThatThrownBy { inTransaction { insertReservation(totalAmount = 0) } }
            .hasStackTraceContaining("살아있는 예매에 좌석이 없다")
    }

    @Test
    fun seat_record_cannot_be_updated() {
        val reservationId = seatHold.hold(accountId, SeatHoldService.Command(performanceId, seatIds.take(1)))

        assertThatThrownBy {
            jdbc.sql("update reservation_seat set price = 1 where reservation_id = :id").param("id", reservationId).update()
        }.hasStackTraceContaining("예매 좌석 기록은 고칠 수 없다")
    }

    @Test
    fun reservation_must_start_as_held() {
        assertThatThrownBy {
            jdbc.sql(
                """
                insert into reservation (account_id, performance_id, status, total_amount, held_until, reserved_at)
                values (:account, :performance, 'reserved', 0, now(), now())
                """,
            ).param("account", accountId).param("performance", performanceId).update()
        }.hasStackTraceContaining("예매는 held 로만 만들 수 있다")
    }

    @Test
    fun transition_outside_the_table_is_rejected() {
        val reservationId = seatHold.hold(accountId, SeatHoldService.Command(performanceId, seatIds.take(1)))

        // held → reserved 는 결제(paying)를 건너뛴 것이다. 트리거가 막지 않으면 돈 안 낸 예매가 성립한다.
        assertThatThrownBy {
            jdbc.sql("update reservation set status = 'reserved', reserved_at = now() where reservation_id = :id").param("id", reservationId).update()
        }.hasStackTraceContaining("할 수 없는 예매 상태 전이다: held -> reserved")
    }

    @Test
    fun idempotency_key_without_a_response_cannot_commit() {
        // 응답 없이 커밋되면 재전송이 빈 답을 받는다. 저장 코드를 빠뜨린 것을 커밋 시점에 잡는다.
        assertThatThrownBy {
            inTransaction {
                jdbc.sql("insert into idempotency_key (account_id, key_value, request_hash) values (:account, 'k', repeat('a', 64))")
                    .param("account", accountId).update()
            }
        }.hasStackTraceContaining("멱등키에 응답이 안 붙었다")
    }

    private fun pointerSet(reservationId: Long): Set<Long> =
        jdbc.sql("select performance_seat_id from performance_seat where reservation_id = :id").param("id", reservationId)
            .query(Long::class.java).list().filterNotNull().toSet()

    private fun recordSet(reservationId: Long): Set<Long> =
        jdbc.sql("select performance_seat_id from reservation_seat where reservation_id = :id").param("id", reservationId)
            .query(Long::class.java).list().filterNotNull().toSet()

    private fun inTransaction(work: () -> Unit) = TransactionTemplate(transactionManager).executeWithoutResult { work() }

    private fun insertReservation(totalAmount: Int): Long =
        jdbc.sql(
            """
            insert into reservation (account_id, performance_id, total_amount, held_until)
            values (:account, :performance, :total, now() + interval '5 minutes')
            returning reservation_id
            """,
        ).param("account", accountId).param("performance", performanceId).param("total", totalAmount)
            .query(Long::class.java).single()

    private fun insertSeatRecord(reservationId: Long, performanceSeatId: Long, price: Int) =
        jdbc.sql("insert into reservation_seat (reservation_id, performance_seat_id, price) values (:r, :s, :p)")
            .param("r", reservationId).param("s", performanceSeatId).param("p", price).update()

    private fun totalOf(reservationId: Long): Int =
        jdbc.sql("select total_amount from reservation where reservation_id = :id").param("id", reservationId).query(Int::class.java).single()
}
