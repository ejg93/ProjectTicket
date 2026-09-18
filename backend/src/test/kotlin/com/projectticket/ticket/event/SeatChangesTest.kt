package com.projectticket.ticket.event

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.reservation.HoldSweeper
import com.projectticket.ticket.reservation.SeatHoldService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 델타와 스냅샷(`10a` 의 닫힘 조건, `D20`).
 *
 * **롤백 없는 바탕이다.** 판은 `afterCommit` 에서 오르므로 커밋이 없으면 아무 일도 안 난다 —
 * 그것이 이 청크의 핵심 계약이다: 롤백된 선점은 화면에 안 보인다.
 */
class SeatChangesTest : ConcurrencyTestBase() {

    @Autowired lateinit var seatQuery: SeatQuery
    @Autowired lateinit var seatVersions: SeatVersions
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var seatHoldService: SeatHoldService
    @Autowired lateinit var sweeper: HoldSweeper
    @Autowired lateinit var redis: StringRedisTemplate

    private lateinit var fixture: EventFixture
    private var performanceId: Long = 0
    private var seatIds: List<Long> = emptyList()
    private var buyer: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("$PREFIX${System.nanoTime()}"))
        val hallId = fixture.hall(venueName = "$PREFIX${System.nanoTime()}")
        fixture.seats(hallId, "F1-A", 4)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
        seatIds = fixture.performanceSeatIds(performanceId)
        buyer = fixture.account("$PREFIX${System.nanoTime()}@test.local")
    }

    @Test
    fun a_hold_shows_up_as_one_changed_seat() {
        val before = seatQuery.version(performanceId)
        hold(seatIds.first())

        val delta = seatQuery.changes(performanceId, since = before)

        assertThat(delta.version).isGreaterThan(before)
        // 2천 석을 다시 보내는 대신 바뀐 한 석만 보낸다(`D20` — 필드 이름이 곧 대역폭이다).
        assertThat(delta.changes).containsExactly(SeatVersions.Change(seatIds.first(), "H"))
    }

    @Test
    fun only_the_last_state_of_a_seat_survives() {
        val before = seatQuery.version(performanceId)
        val reservationId = hold(seatIds.first())
        expire(reservationId)
        sweeper.sweep()

        // 같은 좌석이 두 번 바뀌었다. 화면이 그리는 것은 지금 모습이지 역사가 아니다.
        val delta = seatQuery.changes(performanceId, since = before)
        assertThat(delta.changes).containsExactly(SeatVersions.Change(seatIds.first(), "A"))
    }

    @Test
    fun asking_from_the_current_version_returns_nothing() {
        hold(seatIds.first())
        val now = seatQuery.version(performanceId)

        val delta = seatQuery.changes(performanceId, since = now)

        assertThat(delta.version).isEqualTo(now)
        assertThat(delta.changes).isEmpty()
    }

    @Test
    fun a_since_outside_the_log_is_gone() {
        hold(seatIds.first())

        // 로그 밖을 물으면 410 이다. 조용히 빈 목록을 주면 **화면이 영영 틀린 그림을 든다**.
        assertThatThrownBy { seatQuery.changes(performanceId, since = 0) }
            .isInstanceOfSatisfying(TicketException::class.java) {
                assertThat(it.code.slug).isEqualTo("seat-changes-expired")
                assertThat(it.properties["version"]).isEqualTo(seatQuery.version(performanceId))
            }
    }

    @Test
    fun the_snapshot_is_cached_under_its_version() {
        val version = seatQuery.version(performanceId)
        seatQuery.seatMap(performanceId)

        // 같은 판의 다음 폴링은 Redis 가 답한다 — DB 를 읽는 것은 판이 바뀐 직후 한 번뿐이다(`D20`).
        assertThat(seatVersions.cachedSnapshot(performanceId, version)).isNotNull()
        assertThat(seatQuery.seatMap(performanceId).version).isEqualTo(version)

        hold(seatIds.first())
        // 판이 바뀌면 키가 달라져서 옛 그림이 새 판으로 나갈 수가 없다.
        assertThat(seatVersions.cachedSnapshot(performanceId, seatQuery.version(performanceId))).isNull()
    }

    @Test
    fun a_rolled_back_hold_never_reaches_the_screen() {
        val before = seatQuery.version(performanceId)

        // 없는 좌석을 섞어 선점을 실패시킨다. 트랜잭션이 롤백되므로 `afterCommit` 이 안 돈다.
        runCatching { seatHoldService.hold(buyer, SeatHoldService.Command(performanceId, listOf(seatIds[0], -1L))) }

        assertThat(seatQuery.version(performanceId)).isEqualTo(before)
        assertThat(redis.hasKey("seat:log:$performanceId")).isFalse()
    }

    private fun hold(seatId: Long): Long =
        seatHoldService.hold(buyer, SeatHoldService.Command(performanceId, listOf(seatId)))

    /** 90초를 기다릴 수 없다. 스윕이 집을 수 있게 만료 시각을 뒤로 민다 */
    private fun expire(reservationId: Long) =
        jdbc.sql("update reservation set held_until = now() - interval '1 minute' where reservation_id = :id")
            .param("id", reservationId).update()

    private companion object {
        const val PREFIX = "seat-changes-"
    }
}
