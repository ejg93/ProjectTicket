package com.projectticket.ticket.queue

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/** 회차가 판매 중인지 — DB 가 판정한 값이다(`I13-1`, `D7` 「판정은 SQL now()」) */
data class PerformanceSale(val status: String, val onSale: Boolean)

/**
 * 폴링이 회차 행을 안 읽게 하는 대마다의 캐시(`55`). 폴링 `GET /api/queue/{id}` 가 요청의 97% 라
 * 회차 행 읽기가 Hikari 풀(10)을 같이 말렸다(`load-8`).
 *
 * **판정은 여기서 안 한다.** 담는 것은 DB 가 판정한 [PerformanceSale] 그대로고, 이 클래스는 「얼마나 믿나」만 정한다.
 * 경과는 단조 시계(`System.nanoTime`)로 잰다 — 벽시계가 아니라 대 사이 시계 차와 무관하다(`Elapsed.kt` 와 같은 이유).
 *
 * **판매 중인 값만 담는다.** 판매 전·`draft`·닫힘을 담으면 판매 시작·`draft → open` 순간에 TTL 만큼 늦게 열린다 —
 * 관문이 그동안 안 지켜 토큰 없이 선점이 지나고, 진입은 410 을 받는다(마무리 32차 독립 리뷰). 없는 회차(null)도 안 담는다.
 * 「판매 중」이 낡는 쪽(다른 대가 닫음)은 [QueueService] 가 줄이 걷힌 것을 보고 [evict] 한 뒤 다시 읽는다(`queue-design.md` 「회차 종료」).
 * 같은 키를 동시에 읽는 요청을 하나로 묶지 않는다 — 행 하나 읽기라 묶는 값이 작다(`35j` 는 스냅샷 생성이라 달랐다).
 */
@Component
class PerformanceSaleCache(
    private val ttl: Duration,
    private val ticker: () -> Long,
) {

    private val entries = ConcurrentHashMap<Long, Entry>()

    @Autowired
    constructor() : this(DEFAULT_TTL, System::nanoTime)

    private data class Entry(val sale: PerformanceSale, val expiresAtNanos: Long)

    /** 믿을 수 있는 값이 있으면 그것을, 없으면 [load] 로 읽어 담는다 */
    fun get(performanceId: Long, load: (Long) -> PerformanceSale?): PerformanceSale? {
        val now = ticker()
        entries[performanceId]?.takeIf { now - it.expiresAtNanos < 0 }?.let { return it.sale }

        val sale = load(performanceId)
        if (sale?.onSale == true) entries[performanceId] = Entry(sale, now + ttl.toNanos()) else entries.remove(performanceId)
        return sale
    }

    /** 회차 상태를 바꾼 대가 커밋 뒤에 부른다 — 그 대의 다음 폴링이 바로 410 을 받는다 */
    fun evict(performanceId: Long) {
        entries.remove(performanceId)
    }

    companion object {
        /** 폴링(2초)의 두 바퀴 반. 다른 대가 닫은 회차를 이만큼 늦게 안다 */
        val DEFAULT_TTL: Duration = Duration.ofSeconds(5)
    }
}
