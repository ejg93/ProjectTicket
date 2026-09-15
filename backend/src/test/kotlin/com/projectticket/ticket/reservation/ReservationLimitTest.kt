package com.projectticket.ticket.reservation

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException

/**
 * 1인 N매·중복 선점 제한(ADR 0003). 살아있는 선점은 계정·회차당 하나(`reservation_live_hold_idx`), 회차당 매수는 4.
 *
 * 커밋 레인이다 — 인덱스가 같은 계정의 동시 선점을 직렬화하는 것은 커밋이 실제로 있어야 보인다.
 */
class ReservationLimitTest : ConcurrencyTestBase() {

    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private var accountId: Long = 0
    private var performanceId: Long = 0
    private var seatIds: List<Long> = emptyList()

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        accountId = fixture.account("${PREFIX}limit@test.local")
        val eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        val hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 8)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
        seatIds = fixture.performanceSeatIds(performanceId)
    }

    @Test
    fun second_live_hold_on_the_same_performance_is_rejected() {
        val first = hold(seatIds.take(1))

        assertThatThrownBy { hold(seatIds.drop(1).take(1)) }
            .isInstanceOf(TicketException::class.java)
            .satisfies({ e ->
                e as TicketException
                assertThat(e.code).isEqualTo(ErrorCode.DUPLICATE_HOLD)
                // 화면이 「이미 잡은 예매로 가기」 링크를 그린다(`D5` 추가 필드).
                assertThat(e.properties["reservation_id"]).isEqualTo(first)
            })
        assertThat(liveReservationCount()).isOne()
    }

    @Test
    fun index_itself_rejects_two_live_holds() {
        hold(seatIds.take(1))

        // 서비스를 안 거친 삽입도 막힌다 — 강제 지점이 앱이 아니라 인덱스다(`D14` 축 2).
        assertThatThrownBy {
            jdbc.sql(
                """
                insert into reservation (account_id, performance_id, total_amount, held_until)
                values (:account, :performance, 0, now() + interval '5 minutes')
                """,
            ).param("account", accountId).param("performance", performanceId).update()
        }.isInstanceOf(DuplicateKeyException::class.java).hasStackTraceContaining("reservation_live_hold_idx")
    }

    @Test
    fun confirmed_seats_count_toward_the_per_performance_limit() {
        fixture.reserve(hold(seatIds.take(2)))

        // 확정 뒤에는 같은 회차를 더 살 수 있다 — 남은 한도 안에서.
        val second = hold(seatIds.drop(2).take(2))
        assertThat(second).isPositive()
        fixture.reserve(second)

        assertThatThrownBy { hold(seatIds.drop(4).take(1)) }
            .isInstanceOf(TicketException::class.java)
            .satisfies({ e ->
                e as TicketException
                assertThat(e.code).isEqualTo(ErrorCode.OVER_LIMIT)
                assertThat(e.properties).containsEntry("limit", 4).containsEntry("requested", 5)
            })
    }

    @Test
    fun expired_hold_frees_the_limit() {
        val expired = hold(seatIds.take(4))
        jdbc.sql("update reservation set status = 'expired', expired_at = now() where reservation_id = :id").param("id", expired).update()
        jdbc.sql("update performance_seat set status = 'available', held_until = null, reservation_id = null where reservation_id = :id").param("id", expired).update()

        // 좌석이 돌아간 예매는 매수에 안 센다. 세면 한 번 놓친 사람이 그 회차를 영영 못 산다.
        assertThat(hold(seatIds.drop(4).take(4))).isPositive()
    }

    @Test
    fun concurrent_holds_by_one_account_yield_one() {
        val results = runConcurrently(10) { index -> hold(listOf(seatIds[index % seatIds.size])) }

        // 인덱스가 둘째 insert 를 첫째의 커밋까지 세운다 — 앱 검증이 경합에 안전한 이유다(`V8`).
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.filter { it.isFailure }).hasSize(9).allSatisfy {
            assertThat(it.exceptionOrNull()).isInstanceOf(TicketException::class.java).extracting("code").isEqualTo(ErrorCode.DUPLICATE_HOLD)
        }
        assertThat(liveReservationCount()).isOne()
    }

    private fun hold(seats: List<Long>): Long = seatHold.hold(accountId, SeatHoldService.Command(performanceId, seats))

    private fun liveReservationCount(): Long =
        jdbc.sql("select count(*) from reservation where account_id = :id and status in ('held', 'paying')")
            .param("id", accountId).query(Long::class.java).single()
}
