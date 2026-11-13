package com.projectticket.ticket.error

import io.lettuce.core.RedisBusyException
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisLoadingException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.RedisSystemException

/**
 * 「Redis 가 죽었다」와 「Redis 가 오류로 답했다」를 가르나(`I17-10`).
 *
 * 서버가 답한 오류(스크립트 오류·`WRONGTYPE`)는 장애가 아니라 우리 버그다 — 장애로 보면 락은 `RELEASE` 버그를 WARN 으로 삼키고
 * HTTP 는 버그를 503 `queue-unavailable` 로 낸다. 다만 서버가 바빠서(`BUSY`)·적재 중이라(`LOADING`) 못 받은 것은 장애다.
 */
class RedisFailuresTest {

    @Test
    fun a_server_error_reply_is_not_an_outage() {
        assertThat(RedisSystemException("스크립트 오류", RedisCommandExecutionException("ERR Error running script")).isRedisDown()).isFalse()
    }

    @Test
    fun a_loading_or_busy_server_is_an_outage() {
        assertThat(RedisSystemException("적재 중", RedisLoadingException("LOADING Redis is loading the dataset in memory")).isRedisDown()).isTrue()
        assertThat(RedisSystemException("바쁘다", RedisBusyException("BUSY Redis is busy running a script")).isRedisDown()).isTrue()
    }

    @Test
    fun a_lost_connection_or_timeout_is_an_outage() {
        assertThat(RedisConnectionFailureException("연결 실패").isRedisDown()).isTrue()
        assertThat(QueryTimeoutException("명령 시간 초과", RedisCommandTimeoutException("2초")).isRedisDown()).isTrue()
    }
}
