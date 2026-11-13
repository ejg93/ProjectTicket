package com.projectticket.ticket

import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript
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

    @Test
    fun a_skipping_job_does_nothing_during_an_outage() {
        // 알림은 겹치면 같은 메일이 대수만큼 나간다 — 장애 동안은 건너뛰고 Redis 가 돌아오면 보낸다(`I17-5`, 독립 리뷰 I1).
        `when`(redis.opsForValue()).thenReturn(values)
        `when`(values.setIfAbsent(anyString(), anyString(), any(Duration::class.java))).thenThrow(RedisConnectionFailureException("Redis 가 안 받는다"))
        var ran = false

        assertThat(SchedulerLock(redis).runExclusively("notification-sweeper", onOutage = SchedulerLock.OnOutage.SKIP) { ran = true }).isNull()
        assertThat(ran).isFalse()
    }

    @Test
    fun a_failed_release_does_not_hide_the_result() {
        // 일하는 사이 Redis 가 죽으면 놓기도 던진다. 그것이 일의 결과를 덮으면 스윕이 실패로 보인다 — 락은 TTL 이 지운다.
        `when`(redis.opsForValue()).thenReturn(values)
        `when`(values.setIfAbsent(anyString(), anyString(), any(Duration::class.java))).thenReturn(true)
        `when`(redis.execute(any<RedisScript<Long>>(), anyList(), any())).thenThrow(QueryTimeoutException("Redis 명령 시간 초과", RedisCommandTimeoutException("2초")))

        assertThat(SchedulerLock(redis).runExclusively("hold-sweeper") { WORK_RESULT }).isEqualTo(WORK_RESULT)
    }

    @Test
    fun a_server_error_on_release_is_not_swallowed() {
        // 서버가 오류로 답한 것은 장애가 아니라 우리 버그다(스크립트 오류 — `I17-10`). 삼키면 락이 안 풀리는 버그가 TTL 뒤로 숨는다.
        `when`(redis.opsForValue()).thenReturn(values)
        `when`(values.setIfAbsent(anyString(), anyString(), any(Duration::class.java))).thenReturn(true)
        `when`(redis.execute(any<RedisScript<Long>>(), anyList(), any()))
            .thenThrow(RedisSystemException("스크립트 오류", RedisCommandExecutionException("ERR Error running script")))

        assertThatThrownBy { SchedulerLock(redis).runExclusively("hold-sweeper") { WORK_RESULT } }.isInstanceOf(RedisSystemException::class.java)
    }

    private companion object {
        const val WORK_RESULT = 7
    }
}
