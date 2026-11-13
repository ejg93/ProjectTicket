package com.projectticket.ticket

import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.convert.DurationStyle
import org.springframework.core.io.ClassPathResource
import java.net.ServerSocket
import java.time.Duration

/**
 * Redis 가 **대답을 멈추면** 요청이 정한 시간 안에 실패하나(`I16-15`).
 *
 * 값이 없으면 Lettuce 기본 명령 시간 초과(60초)만큼 세션 필터·대기열 요청이 매달린다. 503 판정(`isRedisDown`)은 이미 있다 —
 * 여기서 보는 것은 **그 판정에 닿기까지 걸리는 시간**이다.
 *
 * 공유 Redis 컨테이너를 멈추지 않는다 — 다른 시험이 같은 컨테이너를 쓴다. 대신 연결은 받고 아무 말도 안 하는 소켓을 세운다.
 * 응답이 끊긴 Redis 와 클라이언트 쪽에서 구별이 안 된다.
 */
class RedisTimeoutTest {

    private val yaml = YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yml")) }.getObject()

    private fun duration(key: String): Duration =
        DurationStyle.detectAndParse(checkNotNull(yaml?.getProperty(key)) { "application.yml 에 $key 가 없다 — 없으면 Lettuce 기본값(60초)이다" })

    @Test
    fun the_command_and_connect_timeouts_are_set() {
        assertThat(duration("spring.data.redis.timeout")).isEqualTo(Duration.ofSeconds(2))
        assertThat(duration("spring.data.redis.connect-timeout")).isEqualTo(Duration.ofSeconds(1))
    }

    @Test
    fun a_silent_redis_fails_within_the_configured_timeout() {
        val commandTimeout = duration("spring.data.redis.timeout")
        val connectTimeout = duration("spring.data.redis.connect-timeout")

        ServerSocket(0).use { silent ->
            val uri = RedisURI.Builder.redis("localhost", silent.localPort).withTimeout(commandTimeout).build()
            val client = RedisClient.create(uri).apply {
                options = ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build()).build()
            }
            try {
                val started = System.nanoTime()
                // 연결 인사(HELLO)에서 먼저 막힐 수도, PING 에서 막힐 수도 있다 — 둘 다 같은 시간 상한을 받는다.
                assertThatThrownBy { client.connect().use { it.sync().ping() } }.isInstanceOf(RedisException::class.java)
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(UPPER_BOUND)
            } finally {
                client.shutdown(Duration.ZERO, Duration.ofSeconds(1))
            }
        }
    }

    private companion object {
        /**
         * 기본값(60초)으로 돌아갔나를 가르는 선. 명령 2초 + 연결 1초에 Netty 첫 기동이 얹힌다 —
         * 빠른 레인 전체와 같이 돌면 3.8초까지 쟀다(`I17-3` 판). 3초로 두면 부하에 흔들린다
         */
        val UPPER_BOUND: Duration = Duration.ofSeconds(10)
    }
}
