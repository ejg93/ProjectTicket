package com.projectticket.ticket.observability

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import org.springframework.stereotype.Component

/**
 * 지표 이름이 사는 한 자리(`D10` 「지표 — 이름 규약」, 30).
 *
 * **이름을 코드 여기저기서 만들면 문서와 어긋난다.** 어긋난 이름은 아무 오류도 안 내고, 대시보드만 조용히 빈다 —
 * 그래서 이름을 상수로 모으고 [com.projectticket.ticket.observability.MetricNamesTest] 가 문서 표와 대조한다.
 *
 * **태그는 값이 유한한 것만** 단다(`D10`). `performance_id` 를 태그로 두면 회차마다 시계열이 늘어 저장소가 터진다 —
 * 회차별로 봐야 하는 것은 로그에 남긴다.
 */
@Component
class TicketMetrics(private val registry: MeterRegistry) {

    /** 게이지는 값을 **읽어 가는** 것이라 최신값을 여기 담아 둔다. 입장 스케줄러가 5초마다 갱신한다 */
    private val queueLength = AtomicLong(0)
    private val queueActive = AtomicLong(0)

    init {
        registry.gauge(QUEUE_LENGTH, queueLength)
        registry.gauge(QUEUE_ACTIVE, queueActive)
    }

    /** @param result `won`·`taken`·`rejected` — 경합에서 이겼나, 남이 먼저 잡았나, 규칙에 막혔나 */
    fun seatHoldAttempt(result: String) = registry.counter(SEAT_HOLD_ATTEMPTS, "result", result).increment()

    /** 선점 트랜잭션이 얼마나 걸렸나. 부하 청크(31)가 이 분포로 p95 를 읽는다 */
    fun seatHoldLatency(elapsed: Duration) = registry.timer(SEAT_HOLD_LATENCY).record(elapsed)

    fun seatSweepExpired(seats: Int) = registry.counter(SEAT_SWEEP_EXPIRED).increment(seats.toDouble())

    fun reservationConfirmed() = registry.counter(RESERVATION_CONFIRMED).increment()

    /** @param reason `audience`·`organizer` — 무엇이 물렀나(26a 의 `cancelled_by` 와 같은 축) */
    fun reservationCancelled(reason: String, count: Int = 1) =
        registry.counter(RESERVATION_CANCELLED, "reason", reason).increment(count.toDouble())

    fun queueAdmitted(people: Long) = registry.counter(QUEUE_ADMITTED).increment(people.toDouble())

    /** 진입부터 입장까지. 줄이 길어지는 것은 게이지가, 「얼마나 기다렸나」는 이 분포가 답한다 */
    fun queueWaited(waited: Duration) = registry.timer(QUEUE_WAIT_SECONDS).record(waited)

    /** @param reason `admission-required`·`admission-mismatch`·`queue-unavailable`(`D5` 의 `type` 슬러그) */
    fun gateRejected(reason: String) = registry.counter(QUEUE_GATE_REJECTED, "reason", reason).increment()

    /** 읽기 모델이 DB 에서 스냅샷을 만든 횟수(`D20`). 이 수가 폴링 수에 비례하면 캐시가 안 먹는 것이다 */
    fun snapshotBuilt() = registry.counter(SEAT_SNAPSHOT_BUILT).increment()

    /** 회차 합이다(`D10`). 회차별 길이는 시계열이 무한히 늘어서 태그로 안 둔다 */
    fun queueSizes(length: Long, active: Long) {
        queueLength.set(length)
        queueActive.set(active)
    }

    companion object {
        const val SEAT_HOLD_ATTEMPTS = "seat.hold.attempts"
        const val SEAT_HOLD_LATENCY = "seat.hold.latency"
        const val SEAT_SWEEP_EXPIRED = "seat.sweep.expired"
        const val RESERVATION_CONFIRMED = "reservation.confirmed"
        const val RESERVATION_CANCELLED = "reservation.cancelled"
        const val QUEUE_LENGTH = "queue.length"
        const val QUEUE_ACTIVE = "queue.active"
        const val QUEUE_ADMITTED = "queue.admitted"
        const val QUEUE_WAIT_SECONDS = "queue.wait_seconds"
        const val QUEUE_GATE_REJECTED = "queue.gate_rejected"
        const val SEAT_SNAPSHOT_BUILT = "seat.snapshot.built"

        /** `D10` 의 표에 있는 이름 전부. 테스트가 이 목록과 문서를 대조한다 */
        val ALL = listOf(
            SEAT_HOLD_ATTEMPTS,
            SEAT_HOLD_LATENCY,
            SEAT_SWEEP_EXPIRED,
            RESERVATION_CONFIRMED,
            RESERVATION_CANCELLED,
            QUEUE_LENGTH,
            QUEUE_ACTIVE,
            QUEUE_ADMITTED,
            QUEUE_WAIT_SECONDS,
            QUEUE_GATE_REJECTED,
            SEAT_SNAPSHOT_BUILT,
        )
    }
}
