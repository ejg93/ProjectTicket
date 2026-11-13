package com.projectticket.ticket

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

/**
 * Redis 가 죽으면 잠금 아래 스케줄러가 **멈추지 않고 잠금 없이 돈다**(`I17-5`).
 *
 * 잠금은 정확성이 아니라 절약이다([SchedulerLock] 머리 주석) — 아래 일이 전부 조건부 UPDATE·Lua 라 겹쳐 돌아도 결과가 같다.
 * 그런데 잠금을 못 잡는다고 던지면 선점 만료(`HoldSweepScheduler`)·회차 종료(`PerformanceCloser`)가 Redis 장애 내내 선다 —
 * 만료된 선점이 좌석을 안 놓는다. DB 만 쓰는 일을 Redis 가 세우는 것이다.
 */
class SchedulerLockTest {

    private val redis = mock(StringRedisTemplate::class.java)

    @Suppress("UNCHECKED_CAST")
    private val values = mock(ValueOperations::class.java) as ValueOperations<String, String>

    @Test
    fun a_redis_outage_runs_the_work_without_the_lock() {
        `when`(redis.opsForValue()).thenReturn(values)
        `when`(values.setIfAbsent(anyString(), anyString(), any(Duration::class.java))).thenThrow(RedisConnectionFailureException("Redis 가 안 받는다"))

        assertThat(SchedulerLock(redis).runExclusively("hold-sweeper") { WORK_RESULT }).isEqualTo(WORK_RESULT)
    }

    @Test
    fun a_failure_that_is_not_an_outage_still_throws() {
        // 장애가 아닌 실패(우리 버그)를 삼키면 잠금이 늘 비어 보인다 — 그것은 숨기지 않는다.
        `when`(redis.opsForValue()).thenReturn(values)
        `when`(values.setIfAbsent(anyString(), anyString(), any(Duration::class.java))).thenThrow(IllegalStateException("직렬화가 깨졌다"))

        assertThatThrownBy { SchedulerLock(redis).runExclusively("hold-sweeper") { WORK_RESULT } }.isInstanceOf(IllegalStateException::class.java)
    }

    private companion object {
        const val WORK_RESULT = 7
    }
}
