package com.projectticket.ticket.reservation

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 같은 좌석을 둘이 못 잡는다 — 이 저장소의 핵심 주장이다(`D4`). 각 스레드가 서비스를 직접 부르고 자기 트랜잭션으로 커밋한다.
 *
 * 「승자 하나」는 **넷 다 본다**(`D8`): 성공 수·실패 종류·좌석 행의 상태와 포인터·기록 행 수. 응답만 보면 「둘 다 성공인데 DB 는 하나」를 놓치고,
 * DB 만 보면 「둘 다 실패인데 좌석은 잡혔다」를 놓친다.
 */
class SeatHoldConcurrencyTest : ConcurrencyTestBase() {

    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    private lateinit var fixture: EventFixture
    private var performanceId: Long = 0
    private var seatIds: List<Long> = emptyList()

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        val hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 4)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
        seatIds = fixture.performanceSeatIds(performanceId)
    }

    @Test
    fun hundred_threads_one_winner() {
        val accounts = (0 until 100).map { fixture.account("$PREFIX$it@test.local") }
        val seat = seatIds.first()

        val results = runConcurrently(100) { index ->
            seatHold.hold(accounts[index], SeatHoldService.Command(performanceId, listOf(seat)))
        }

        val winners = results.filter { it.isSuccess }
        assertThat(winners).describedAs("성공은 정확히 하나다").hasSize(1)
        assertThat(results.filter { it.isFailure })
            .hasSize(99)
            .allSatisfy { failure ->
                assertThat(failure.exceptionOrNull()).isInstanceOf(TicketException::class.java)
                    .extracting("code").isEqualTo(ErrorCode.SEAT_TAKEN)
            }

        val reservationId = winners.single().getOrThrow()
        assertThat(seatRow(seat)).isEqualTo("held" to reservationId)
        assertThat(count("reservation_seat where performance_seat_id = $seat")).isEqualTo(1)
        // 진 쪽의 예매 행이 남으면 「예매 행 → 조건부 UPDATE → 롤백」이 한 트랜잭션이 아닌 것이다.
        assertThat(count("reservation where performance_id = $performanceId")).isEqualTo(1)
    }

    /**
     * 정렬을 지워도 위 테스트는 통과할 수 있다 — 플래너가 두 요청에 같은 순서를 돌려주면 순환이 안 생긴다.
     * 그래서 **잠그는 순서를 손으로 엇갈리게 만들어** 교착이 실재함을 못박는다(`D8`). 이것이 빨개지면 Postgres 가 교착을 안 잡는 것이다.
     */
    @Test
    fun opposite_lock_order_deadlocks_without_sorting() {
        val (first, second) = seatIds.take(2)
        val bothHoldOne = CountDownLatch(2)

        val results = runConcurrently(2) { index ->
            TransactionTemplate(transactionManager).execute {
                val mine = if (index == 0) listOf(first, second) else listOf(second, first)
                lock(mine[0])
                bothHoldOne.countDown()
                bothHoldOne.await(10, TimeUnit.SECONDS)
                lock(mine[1])
            }
        }

        assertThat(results.count { sqlStateOf(it.exceptionOrNull()) == "40P01" })
            .describedAs("한쪽은 교착으로 죽어야 한다. 둘 다 살면 교착이 안 났고 둘 다 죽으면 판정이 이상하다")
            .isEqualTo(1)
    }

    /** 같은 좌석 둘을 반대 순서로 요청해도 서비스가 id 순으로 잠그므로 교착이 아니라 「남이 이겼다」로 끝난다 */
    @Test
    fun opposite_request_order_does_not_deadlock_with_sorting() {
        val (first, second) = seatIds.take(2)
        val accounts = listOf(fixture.account("${PREFIX}a@test.local"), fixture.account("${PREFIX}b@test.local"))

        val results = runConcurrently(2) { index ->
            val order = if (index == 0) listOf(first, second) else listOf(second, first)
            seatHold.hold(accounts[index], SeatHoldService.Command(performanceId, order))
        }

        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.mapNotNull { sqlStateOf(it.exceptionOrNull()) }).describedAs("교착이 없어야 한다").isEmpty()
        assertThat(results.filter { it.isFailure }).hasSize(1).allSatisfy {
            assertThat(it.exceptionOrNull()).isInstanceOf(TicketException::class.java).extracting("code").isEqualTo(ErrorCode.SEAT_TAKEN)
        }
    }

    private fun lock(performanceSeatId: Long) =
        jdbc.sql("select performance_seat_id from performance_seat where performance_seat_id = :id for update")
            .param("id", performanceSeatId).query(Long::class.java).single()

    private fun seatRow(performanceSeatId: Long): Pair<String, Long?> =
        jdbc.sql("select status, reservation_id from performance_seat where performance_seat_id = :id")
            .param("id", performanceSeatId)
            .query { rs, _ -> rs.getString("status") to rs.getObject("reservation_id", Long::class.javaObjectType) }
            .single()

    private fun count(fromWhere: String): Long =
        jdbc.sql("select count(*) from $fromWhere").query(Long::class.java).single()

    private fun sqlStateOf(e: Throwable?): String? =
        generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState
}
