package com.projectticket.ticket.event

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript

/**
 * Redis 장애로 못 올린 판을 고침 회가 RESET 으로 되살리나(`I17-9`, `D20` 「쓰기 쪽이 하는 일」).
 *
 * 델타는 로그에 적힌 좌석만 준다 — 올리기를 놓친 좌석은 뒤 델타 어디에도 안 나오고 판도 그대로라 `ETag` 304 가 이어진다.
 * 그래서 못 올린 회차를 표시해 두고, 로그를 비우고 판을 올려(RESET) 받는 쪽이 410 으로 전체를 다시 읽게 한다.
 */
class SeatVersionsTest {

    private val redis = mock(StringRedisTemplate::class.java)

    @Suppress("UNCHECKED_CAST")
    private val values = mock(ValueOperations::class.java) as ValueOperations<String, String>

    // DB 는 안 닿는다 — 판이 Redis 에 있어 씨 뿌리기(`databaseVersion`)를 안 부른다. 형은 생성자가 정한다.
    private val versions = SeatVersions(redis, mock())

    @Test
    fun a_missed_bump_is_repaired_by_a_reset() {
        `when`(redis.opsForValue()).thenThrow(RedisConnectionFailureException("Redis 가 안 받는다")).thenReturn(values)
        `when`(values.get(anyString())).thenReturn("5")

        versions.publishAfterCommit(listOf(SeatVersions.Row(performanceSeatId = 1, performanceId = PERFORMANCE)), PerformanceSeatStatus.HELD)

        assertThat(versions.repairDirty()).isEqualTo(1)
        verify(redis, times(1)).execute(any<RedisScript<Long>>(), anyList())
        // 고쳤으면 표시가 지워진다 — 다음 회는 할 일이 없다.
        assertThat(versions.repairDirty()).isZero()
    }

    @Test
    fun a_failed_reset_keeps_the_mark_for_the_next_round() {
        `when`(redis.opsForValue()).thenReturn(values)
        `when`(values.get(anyString())).thenReturn("5")
        `when`(redis.execute(any<RedisScript<Long>>(), anyList()))
            .thenThrow(RedisConnectionFailureException("아직 죽어 있다"))
            .thenReturn(6L)
        versions.markDirty(PERFORMANCE)

        assertThat(versions.repairDirty()).isZero()
        assertThat(versions.repairDirty()).describedAs("Redis 가 돌아오면 다음 회가 고친다").isEqualTo(1)
    }

    private companion object {
        const val PERFORMANCE = 7L
    }
}
