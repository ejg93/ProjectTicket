package com.projectticket.ticket.outbox

import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/**
 * 소비가 실패하면 어떻게 되나(29, `D11` 「처리 실패는 재시도, 초과하면 DLQ」).
 *
 * **28 까지는 소비자가 예외를 삼켰다.** 그때는 그것이 맞았다 — 재시도할 자리가 없었고 삼키지 않으면 발행자가 멈췄다.
 * 이제 브로커가 사이에 있어서 **삼키면 그 사건이 조용히 사라진다.** 그래서 소비자는 던지고, 여기가 받는다.
 *
 * | 무엇 | 값 | 왜 |
 * |---|---|---|
 * | 재시도 | 2초 간격 3회 | 잠깐 끊긴 DB·잠금 경합은 그 사이에 풀린다. 더 길게 끌면 뒤 사건이 밀린다 |
 * | 초과하면 | `<토픽>.DLT` 로 보낸다 | 그 줄이 막히면 **소비자 하나의 결함이 발행자를 멈춘다** — `D11` 이 막으라고 한 것이다 |
 * | 오프셋 | DLT 로 보낸 뒤 넘어간다 | 안 넘기면 같은 사건을 영원히 다시 읽는다 |
 *
 * **재시도가 안전한 이유는 소비자가 멱등이라서다**(`D11`) — 알림은 `(event_id, account_id)` 유일,
 * 정산은 `performance_id` 유일이라 두 번째 시도가 행을 두 개로 만들지 않는다.
 *
 * DLT 에 쌓인 것을 다시 넣는 길은 **아웃박스다**. 사건 행이 DB 에 남아 있어서 `published_at` 을 비우면 릴레이가 다시 보낸다 —
 * 그 손잡이는 사람이 쓰는 것이라 지금은 API 를 안 만든다(대전제 3).
 */
@Configuration(proxyBeanMethods = false)
class ConsumerErrorHandling {

    private val log = LoggerFactory.getLogger(ConsumerErrorHandling::class.java)

    @Bean
    fun kafkaErrorHandler(template: KafkaTemplate<String, String>): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(template) { record, exception ->
            log.warn(
                "소비를 포기하고 DLT 로 보낸다 topic={} partition={} offset={} 이유={}",
                record.topic(),
                record.partition(),
                record.offset(),
                exception.javaClass.simpleName,
            )
            // **파티션 0 으로 못 박는다.** 원래 파티션 번호를 그대로 쓰면 DLT 의 파티션 수가 적을 때 보내기가 실패한다.
            TopicPartition("${record.topic()}${KafkaTopicsConfig.DEAD_LETTER_SUFFIX}", 0)
        }

        return DefaultErrorHandler(recoverer, FixedBackOff(RETRY_INTERVAL_MS, RETRY_COUNT))
    }

    private companion object {
        const val RETRY_INTERVAL_MS = 2_000L
        const val RETRY_COUNT = 3L
    }
}
