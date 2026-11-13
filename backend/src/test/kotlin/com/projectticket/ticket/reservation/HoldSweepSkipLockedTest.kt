package com.projectticket.ticket.reservation

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 만료 스윕이 남이 쥔 예매를 기다리지 않나(`I17-8`, `D4` 「여러 행을 잠글 때」).
 *
 * 스윕은 만료된 예매 전부를 한 문장에 임의 순서로 잠갔다. 회차 취소는 `order by reservation_id for update` 로 잠그므로 둘이 엇갈리면
 * `40P01` 이다. 스윕이 `skip locked` 로 안 기다리면 교착 고리에 못 든다 — 남이 쥔 행은 그쪽이 끝내고, 나머지는 다음 회다.
 * 커밋 레인이다: 다른 트랜잭션이 정말로 행을 쥐고 있어야 한다.
 */
class HoldSweepSkipLockedTest : ConcurrencyTestBase() {

    @Autowired lateinit var sweeper: HoldSweeper
    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var txManager: PlatformTransactionManager

    private var reservations: List<Long> = emptyList()

    @BeforeEach
    fun holdThree() {
        val fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("${PREFIX}sweep-skip"))
        val hallId = fixture.hall(venueName = "${PREFIX}sweep-skip-${System.nanoTime()}")
        fixture.seats(hallId, "F1-A", 3)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        val performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
        reservations = fixture.performanceSeatIds(performanceId).mapIndexed { index, seatId ->
            val accountId = fixture.account("${PREFIX}sweep-skip-$index-${System.nanoTime()}@test.local")
            seatHold.hold(accountId, SeatHoldService.Command(performanceId, listOf(seatId)))
        }.sorted()
        // 남이 남긴 만료 예매를 먼저 치운다 — 한 회 상한을 재는 시험이 남의 행에 자리를 뺏기지 않게.
        sweeper.sweep()
        jdbc.sql("update reservation set held_until = now() - interval '1 second' where reservation_id in (:ids)").param("ids", reservations).update()
    }

    @Test
    fun a_locked_reservation_is_skipped_not_waited_for() {
        val locked = reservations.first()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        val holding = holder.submit {
            TransactionTemplate(txManager).executeWithoutResult {
                jdbc.sql("select 1 from reservation where reservation_id = :id for update").param("id", locked).query(Int::class.java).single()
                held.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        try {
            check(held.await(10, TimeUnit.SECONDS)) { "예매 행을 못 잡았다" }

            val started = System.nanoTime()
            sweeper.sweep()
            val tookMs = (System.nanoTime() - started) / 1_000_000

            assertThat(tookMs).describedAs("남이 쥔 행을 기다리면 그 트랜잭션이 끝날 때까지 선다").isLessThan(1_000)
            assertThat(statusOf(locked)).describedAs("쥔 쪽(취소·종료·확정)이 끝낸다").isEqualTo("held")
            assertThat(reservations.drop(1).map(::statusOf)).containsOnly("expired")
        } finally {
            release.countDown()
            holding.get(30, TimeUnit.SECONDS)
            holder.shutdown()
        }
    }

    @Test
    fun one_round_takes_at_most_the_batch_oldest_deadline_first() {
        // id 가 앞선 예매의 기한이 가장 덜 지났다 — id 순서로 고르면 그것부터 간다(`I17-15`).
        listOf(1, 3, 2).zip(reservations).forEach { (secondsAgo, id) ->
            jdbc.sql("update reservation set held_until = now() - make_interval(secs => :secs) where reservation_id = :id")
                .param("secs", secondsAgo)
                .param("id", id)
                .update()
        }

        sweeper.sweep(batch = 2)

        assertThat(reservations.map(::statusOf)).containsExactly("held", "expired", "expired")
    }

    private fun statusOf(reservationId: Long): String =
        jdbc.sql("select status from reservation where reservation_id = :id").param("id", reservationId).query(String::class.java).single()
}
