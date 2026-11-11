package com.projectticket.ticket.queue

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 회차 판매 상태 캐시(`55`). 시계는 가짜 경과(나노초)다 — TTL 을 인자로 받아 줄이는 꼴(`D8` 「오늘 날짜」).
 */
class PerformanceSaleCacheTest {

    private var nanos = 0L
    private val cache = PerformanceSaleCache(ttl = Duration.ofSeconds(5), ticker = { nanos })
    private var loads = 0

    private fun loader(sale: PerformanceSale?): (Long) -> PerformanceSale? = {
        loads++
        sale
    }

    private fun advance(duration: Duration) {
        nanos += duration.toNanos()
    }

    @Test
    fun a_second_read_within_the_ttl_does_not_load() {
        val load = loader(ON_SALE)

        assertThat(cache.get(ID, load)).isEqualTo(ON_SALE)
        advance(Duration.ofMillis(4_999))
        assertThat(cache.get(ID, load)).isEqualTo(ON_SALE)

        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun a_read_after_the_ttl_loads_again() {
        val load = loader(ON_SALE)

        cache.get(ID, load)
        advance(Duration.ofSeconds(5))
        cache.get(ID, load)

        assertThat(loads).isEqualTo(2)
    }

    @Test
    fun evict_makes_the_next_read_load() {
        val load = loader(ON_SALE)

        cache.get(ID, load)
        cache.evict(ID)
        cache.get(ID, load)

        assertThat(loads).isEqualTo(2)
    }

    @Test
    fun a_missing_performance_is_not_cached() {
        val load = loader(null)

        assertThat(cache.get(ID, load)).isNull()
        assertThat(cache.get(ID, load)).isNull()

        assertThat(loads).isEqualTo(2)
    }

    @Test
    fun an_entry_before_sales_open_ends_at_the_opening() {
        // 판매 시작 1초 전에 읽은 「아직 아니다」는 TTL 5초를 다 안 산다 — 시작 순간에 다시 읽어야 열린다(`D7` 판정은 DB)
        val before = PerformanceSale(status = "open", onSale = false, untilOpen = Duration.ofSeconds(1))
        val load = loader(before)

        cache.get(ID, load)
        advance(Duration.ofMillis(999))
        cache.get(ID, load)
        assertThat(loads).isEqualTo(1)

        advance(Duration.ofMillis(1))
        cache.get(ID, load)
        assertThat(loads).isEqualTo(2)
    }

    @Test
    fun entries_are_per_performance() {
        val load = loader(ON_SALE)

        cache.get(ID, load)
        cache.get(ID + 1, load)

        assertThat(loads).isEqualTo(2)
    }

    private companion object {
        const val ID = 7L
        val ON_SALE = PerformanceSale(status = "open", onSale = true, untilOpen = Duration.ZERO)
    }
}
