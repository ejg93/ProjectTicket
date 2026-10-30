package com.projectticket.ticket.reservation

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.payment.PerformanceCancelService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 회차 취소와 선점이 겹칠 때 취소된 회차에 살아 있는 예매가 남지 않는다(`I13-1`, `D4` 「좌석 선점」).
 *
 * 창은 이렇게 열렸다 — 취소가 `update performance` 를 하고 아직 커밋하지 않은 사이에 선점이 들어오면, 선점의 `exists(open)` 은
 * 문장 스냅샷이라 회차를 `open` 으로 읽고, 취소의 `for update` 는 아직 커밋 안 된 예매를 못 본다. 둘 다 커밋하면 취소된 회차에 `held` 가 남는다.
 *
 * 취소를 바깥 트랜잭션에 붙들어 그 창을 일부러 길게 연다. 선점은 회차 행의 `for share` 에서 취소가 끝나기를 기다렸다가 `performance-not-open` 을 받아야 한다.
 * 선점이 기다리는 시간은 [HOLD_OPEN_MS]. `for share` 의 기다림에는 상한이 없다 — `lock_timeout` 2초는 멱등키 잠금에만 걸고 되돌린다(`I11-1`).
 * 상한을 둘지는 `I13-9` 가 오픈 순간을 재며 정한다.
 */
class PerformanceCancelConcurrencyTest : ConcurrencyTestBase() {

    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var cancelService: PerformanceCancelService
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var txManager: PlatformTransactionManager

    private lateinit var fixture: EventFixture
    private var performanceId: Long = 0
    private var seatIds: List<Long> = emptyList()

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        val hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
        seatIds = fixture.performanceSeatIds(performanceId)
    }

    @Test
    fun a_hold_racing_a_cancel_leaves_no_live_reservation() {
        val buyer = fixture.account("${PREFIX}buyer@test.local")
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)

        val cancel = CompletableFuture.runAsync {
            TransactionTemplate(txManager).executeWithoutResult {
                cancelService.cancel(performanceId, actorAccountId = null)
                cancelled.countDown()
                release.await(WAIT_SECONDS, TimeUnit.SECONDS)
            }
        }
        assertThat(cancelled.await(WAIT_SECONDS, TimeUnit.SECONDS)).describedAs("취소가 시작하지 못했다").isTrue()

        val hold = CompletableFuture.supplyAsync {
            runCatching { seatHold.hold(buyer, SeatHoldService.Command(performanceId, seatIds.take(1))) }
        }
        // 선점이 막혀 있든 지나갔든 이만큼 뒤에 취소를 커밋한다.
        runCatching { hold.get(HOLD_OPEN_MS, TimeUnit.MILLISECONDS) }
        release.countDown()
        cancel.get(WAIT_SECONDS, TimeUnit.SECONDS)
        val result = hold.get(WAIT_SECONDS, TimeUnit.SECONDS)

        assertThat(liveReservations())
            .withFailMessage { "취소된 회차에 살아 있는 예매가 남았다 — 선점이 커밋 전 취소를 `open` 으로 읽었다: ${liveReservations()}" }
            .isEmpty()
        assertThat((result.exceptionOrNull() as? TicketException)?.code)
            .withFailMessage { "선점이 performance-not-open 으로 끝나지 않았다: $result" }
            .isEqualTo(ErrorCode.PERFORMANCE_NOT_OPEN)
    }

    private fun liveReservations(): List<Long> =
        jdbc.sql("select reservation_id from reservation where performance_id = :id and status in ('held', 'paying', 'reserved')")
            .param("id", performanceId)
            .query(Long::class.java)
            .list()
            .filterNotNull()

    private companion object {
        const val WAIT_SECONDS = 10L

        /** 선점에게 주는 시간. 막히지 않았다면 이 안에 끝난다 */
        const val HOLD_OPEN_MS = 700L
    }
}
