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
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.convert.DurationStyle
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
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
    fun the_boot_wired_connection_factory_carries_both_timeouts() {
        // 이름만 묶으면 커스텀 빈이 설정을 무시해도 초록이다 — Boot 자동 설정이 실제로 세운 빈을 본다(`I17-10`, 마무리 35차 독립 리뷰 I3).
        // 명령 상한은 소켓으로 따로 못 잰다(아래 시험) — 빈의 `commandTimeout` 이 Lettuce 가 쓰는 그 값이다.
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration::class.java))
            .run { context ->
                val factory = context.getBean(LettuceConnectionFactory::class.java)
                assertThat(factory.clientConfiguration.commandTimeout).isEqualTo(Duration.ofSeconds(2))
                val socket = factory.clientConfiguration.clientOptions.map { it.socketOptions }.orElseThrow()
                assertThat(socket.connectTimeout).isEqualTo(Duration.ofSeconds(1))
            }
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
                // **명령 상한(2초)을 따로 재지 못한다**(마무리 35차 독립 리뷰 I3) — 말 없는 소켓 앞에서 Lettuce 는 연결 단계에서 먼저 막히고,
                // RESP2 로 붙어도 같았다. 여기서 가르는 것은 「기본값 60초가 아니다」뿐이다. 명령 상한과 Boot 배선은 `I17-10` 이 잰다.
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
