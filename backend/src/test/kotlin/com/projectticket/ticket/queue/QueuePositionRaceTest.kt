package com.projectticket.ticket.queue

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 입장하는 순간에 폴링이 404 를 받지 않는가(`35g`).
 *
 * 전에는 폴링이 토큰을 보고, 없으면 줄 순번을 **따로** 읽었다. 그 사이에 입장 스크립트(`ZPOPMIN` + 토큰 `SET`)가 끼면
 * 토큰도 없고 줄에도 없어 404 `not-in-queue` 였다 — 화면은 「대기열에서 빠졌습니다」로 읽는다. 줄 1만에서 8번 났다(`load-3` 「35c」).
 *
 * **롤백 없는 바탕이다** — 폴링 스레드들이 회차 상태를 DB 에서 읽어서, 커밋 안 된 회차는 404 `performance-not-found` 다.
 * Redis 는 이 바탕이 안 비운다 — 끝에 회차의 키(`QueueKeys.ofPerformance`)를 지운다. 토큰 키(`admit:*` 4,000개)는 회차로 못 훑어서
 * TTL 10분과 다음 `PostgresTestBase` 의 `flushDb` 가 걷는다 — 회차 id 가 유일해 다른 시험에 안 닿는다.
 */
class QueuePositionRaceTest : ConcurrencyTestBase() {

    @Autowired lateinit var queue: QueueService
    @Autowired lateinit var admission: AdmissionService
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var redis: StringRedisTemplate

    private var performanceId: Long = 0

    @AfterEach
    fun dropLine() {
        if (performanceId != 0L) queue.drop(performanceId)
    }

    @Test
    fun polling_never_falls_between_the_token_and_the_line() {
        val fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        val hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 1)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)

        // 계정 행은 필요 없다 — 줄의 원소는 계정 id 문자열이고 순번 조회는 계정을 안 읽는다.
        val accounts = (1..LINE).map { BASE_ACCOUNT + it }
        accounts.forEachIndexed { i, id -> redis.opsForZSet().add(QueueKeys.waiting(performanceId), id.toString(), i.toDouble()) }

        val done = AtomicBoolean(false)
        val admitted = AtomicInteger(0)
        val results = runConcurrently(POLLERS + 1) { index ->
            if (index == POLLERS) admitAll(done, admitted) else pollFront(accounts, done, admitted)
        }

        val missing = results.sumOf { it.getOrThrow() }
        assertThat(missing).describedAs("입장하는 순간의 폴링이 「줄에 없다」를 받았다 — 토큰과 순번을 따로 읽는다(`35g`)").isZero()
        assertThat(accounts.count { admission.tokenFor(performanceId, it) != null }).isEqualTo(LINE)
    }

    /** 문지기 하나 — 배치를 빨리 거듭 들인다(예산의 창을 지워 5초를 안 기다린다). 사이를 조금 띄워 폴링이 끼게 한다 */
    private fun admitAll(done: AtomicBoolean, admitted: AtomicInteger): Int {
        try {
            while (waitingCount() > 0) {
                redis.delete(QueueKeys.admitWindow(performanceId))
                admitted.addAndGet(admission.admit(performanceId).toInt())
                Thread.sleep(ADMIT_GAP_MS)
            }
        } finally {
            // 문지기가 예외로 끝나도 폴러가 시한까지 돌지 않게 — 원인이 시간 초과에 가려진다
            done.set(true)
        }
        return 0
    }

    /** 폴러 하나. 문지기가 끝날 때까지 묻고 「줄에 없다」를 받은 수를 준다 */
    private fun pollFront(accounts: List<Long>, done: AtomicBoolean, admitted: AtomicInteger): Int {
        var missing = 0
        while (!done.get()) {
            // **다음 배치에 들 사람만 묻는다** — 줄 맨 앞 한 배치. 아무나 물으면 입장 순간에 걸릴 확률이 배치 ÷ 줄이라 경합이 안 난다
            val front = admitted.get()
            val reach = minOf(AdmissionService.BATCH_SIZE.toInt(), LINE - front).coerceAtLeast(1)
            val id = accounts[minOf(front + ThreadLocalRandom.current().nextInt(reach), LINE - 1)]
            try {
                queue.position(performanceId, id)
            } catch (e: TicketException) {
                if (e.code == ErrorCode.NOT_IN_QUEUE) missing++ else throw e
            }
        }
        return missing
    }

    private fun waitingCount(): Long = redis.opsForZSet().size(QueueKeys.waiting(performanceId)) ?: 0

    private companion object {
        const val LINE = 2_000 // 정원(2,000) 안 — 넘으면 입장이 정원에서 멈춘다
        const val POLLERS = 8
        const val ADMIT_GAP_MS = 20L
        const val BASE_ACCOUNT = 7_000_000_000L
    }
}
