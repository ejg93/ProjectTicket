package com.projectticket.ticket

import org.apache.kafka.clients.producer.ProducerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.autoconfigure.task.TaskSchedulingProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.convert.DurationStyle
import org.springframework.core.io.ClassPathResource

/**
 * 아웃박스 릴레이가 Kafka 를 기다리는 동안 **다른 스케줄러가 멈추지 않나**(`I17-3`).
 *
 * `@Scheduled` 11 개가 Boot 기본 스레드 하나를 나누면 Kafka 장애 때 릴레이의 `send().join()` 이 60~120초 막히고,
 * 그동안 선점 만료·대기열 입장이 같이 선다. 막는 것은 둘이다 — 풀을 늘린다, 릴레이가 막히는 시간에 상한을 둔다.
 *
 * 설정을 글자로만 보면 키 이름이 틀려도 초록이다. 그래서 Boot 의 실제 속성 클래스·Kafka 의 실제 설정 이름에 묶어 본다.
 */
class SchedulingConfigTest {

    private val binder = Binder(
        MapConfigurationPropertySource(
            checkNotNull(YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yml")) }.getObject()) { "application.yml 을 못 읽었다" },
        ),
    )

    private val producer: Map<String, String> =
        binder.bind("spring.kafka.producer.properties", Bindable.mapOf(String::class.java, String::class.java)).orElse(null).orEmpty()

    private fun producerMillis(name: String): Long =
        checkNotNull(producer[name]) { "spring.kafka.producer.properties.$name 가 없다 — 없으면 Kafka 기본값이다" }.toLong()

    @Test
    fun scheduled_jobs_do_not_share_a_single_thread() {
        val scheduling = binder.bind("spring.task.scheduling", TaskSchedulingProperties::class.java).orElseGet(::TaskSchedulingProperties)
        assertThat(scheduling.pool.size).isEqualTo(4)
    }

    @Test
    fun producer_settings_are_real_kafka_names() {
        assertThat(producer.keys).isNotEmpty().allMatch { it in ProducerConfig.configNames() }
    }

    @Test
    fun the_relay_cannot_block_past_the_shutdown_phase() {
        assertThat(producerMillis(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(5_000)
        assertThat(producerMillis(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG)).isEqualTo(5_000)
        val delivery = producerMillis(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)
        assertThat(delivery).isEqualTo(15_000)

        // Kafka 는 `delivery.timeout.ms < linger.ms + request.timeout.ms` 면 프로듀서를 안 만든다 — 기동이 깨진다.
        val linger = producer[ProducerConfig.LINGER_MS_CONFIG]?.toLong() ?: KAFKA_DEFAULT_LINGER_MS
        assertThat(delivery).isGreaterThanOrEqualTo(producerMillis(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG) + linger)

        // 종료 단계 안에 끝나야 파드가 릴레이 트랜잭션 중간에 죽지 않는다.
        val shutdownPhase = DurationStyle.detectAndParse(
            checkNotNull(binder.bind("spring.lifecycle.timeout-per-shutdown-phase", String::class.java).orElse(null)) { "종료 단계 상한이 없다" },
        )
        assertThat(delivery).isLessThan(shutdownPhase.toMillis())
    }

    private companion object {
        /** Kafka 4 의 `linger.ms` 기본값. 3.x 는 0 이었다 — 큰 쪽으로 잡는다 */
        const val KAFKA_DEFAULT_LINGER_MS = 5L
    }
}
