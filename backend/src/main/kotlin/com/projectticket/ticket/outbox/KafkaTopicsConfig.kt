package com.projectticket.ticket.outbox

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * 토픽을 **우리가 만든다**(29). 브로커의 자동 생성에 맡기면 파티션이 기본값 하나로 생겨서
 * ADR 0007 이 정한 셋과 갈린다 — 실제로 28 뒤에 확인해 보니 1개였다.
 *
 * 파티션 셋인 이유는 인스턴스 셋과 맞추려는 것이다(ADR 0007). 더 늘리면 소비자가 놀고, 줄이면 한 대가 둘을 든다.
 *
 * **DLT 는 한 파티션이다.** 죽은 편지는 순서를 지킬 이유가 없고, 사람이 읽는 줄이라 한 줄이 낫다.
 */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {

    @Bean
    fun reservationTopic(): NewTopic = topic(EventTopics.RESERVATION)

    @Bean
    fun performanceTopic(): NewTopic = topic(EventTopics.PERFORMANCE)

    @Bean
    fun reservationDeadLetterTopic(): NewTopic = deadLetterTopic(EventTopics.RESERVATION)

    @Bean
    fun performanceDeadLetterTopic(): NewTopic = deadLetterTopic(EventTopics.PERFORMANCE)

    private fun topic(name: String): NewTopic =
        TopicBuilder.name(name).partitions(PARTITIONS).replicas(REPLICAS).build()

    private fun deadLetterTopic(name: String): NewTopic =
        TopicBuilder.name("$name$DEAD_LETTER_SUFFIX").partitions(1).replicas(REPLICAS).build()

    companion object {
        /** 소비자가 버린 편지가 가는 곳. Spring 의 기본 이름 규칙(`.DLT`)을 따른다 */
        const val DEAD_LETTER_SUFFIX = ".DLT"

        private const val PARTITIONS = 3

        /** 로컬 브로커 한 대다(ADR 0007 「지금 안 하는 것」). 복제는 그 한 대가 전부다 */
        private const val REPLICAS = 1
    }
}
