package com.projectticket.ticket.outbox

import com.projectticket.ticket.error.hitStatementLimit
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionException
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.ExponentialBackOff
import org.springframework.util.backoff.FixedBackOff
import tools.jackson.core.JacksonException

/**
 * 소비가 실패하면 어떻게 되나(29, `D11` 「처리 실패는 재시도, 초과하면 DLQ」).
 *
 * **28 까지는 소비자가 예외를 삼켰다.** 그때는 그것이 맞았다 — 재시도할 자리가 없었고 삼키지 않으면 발행자가 멈췄다.
 * 이제 브로커가 사이에 있어서 **삼키면 그 사건이 조용히 사라진다.** 그래서 소비자는 던지고, 여기가 받는다.
 *
 * | 무엇 | 값 | 왜 |
 * |---|---|---|
 * | 재시도 | 2초 간격 3회 | 사건 탓으로 보이는 실패. 더 길게 끌면 뒤 사건이 밀린다 |
 * | DB 연결 실패 | 2초부터 두 배, 최대 30초, **횟수 무제한** | DB 가 3회 창보다 오래 죽으면 그동안 소비한 사건이 전부 DLT 로 갔다(`I17-7`). 30초는 `max.poll.interval.ms` 300초 안이다 |
 * | 그 밖 DB 일시 오류 | 같은 지수, **대기 합 5분**(대기 13번, 전달 14번) 뒤 DLT | 문장 상한(`55P03`·`57014`)·교착은 DB 가 살아 있어도 늘 걸릴 수 있다 — 무제한이면 그 파티션이 영영 선다(`I17-12`, 2026-11-13 사용자). 대기 합은 같은 예외 종류가 이어질 때만 쌓인다 — 종류가 바뀌면 spring-kafka 가 대기를 새로 고른다(`resetStateOnExceptionChange`, 마무리 37차 m3) |
 * | 깨진 봉투 | 재시도 없이 바로 DLT | JSON 이 깨진 것은 다시 읽어도 깨져 있다(`I17-7`) |
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
            // ERROR 다 — DLT 에 든 사건은 사람이 아웃박스로 다시 넣어야 끝난다(`D10` 「ERROR 는 사람이 고쳐야 하는 것」, `I11-12`).
            log.error(
                "소비를 포기하고 DLT 로 보낸다 topic={} partition={} offset={} 이유={}",
                record.topic(),
                record.partition(),
                record.offset(),
                exception.javaClass.simpleName,
            )
            // **파티션 0 으로 못 박는다.** 원래 파티션 번호를 그대로 쓰면 DLT 의 파티션 수가 적을 때 보내기가 실패한다.
            TopicPartition("${record.topic()}${KafkaTopicsConfig.DEAD_LETTER_SUFFIX}", 0)
        }

        return DefaultErrorHandler(recoverer, FixedBackOff(RETRY_INTERVAL_MS, RETRY_COUNT)).apply {
            setBackOffFunction { _, exception -> backOffFor(exception) }
        }
    }

    companion object {
        private const val RETRY_INTERVAL_MS = 2_000L
        private const val RETRY_COUNT = 3L
        private const val TRANSIENT_INITIAL_MS = 2_000L
        private const val TRANSIENT_MAX_MS = 30_000L
        private const val TRANSIENT_MULTIPLIER = 2.0

        /** 연결 실패가 아닌 일시 오류의 대기 합 상한 — 5분(2026-11-13 사용자, `I17-12`). 시도 자체(문장 10초)는 안 센다 */
        const val TRANSIENT_MAX_ELAPSED_MS = 300_000L

        /**
         * 실패한 기록마다 기다림을 고른다. 원인 사슬로 가른다 — 리스너 예외는 `ListenerExecutionFailedException` 에 싸여 온다.
         * 분류를 이 함수 하나에 둔다(`addNotRetryableExceptions` 를 따로 안 쓴다) — 시험이 고르는 자리를 그대로 잰다.
         *
         * 순서가 판정이다: 깨진 봉투 → 문장 상한 → 연결 실패 → 그 밖 일시 오류 → 나머지. 문장 상한을 연결 실패보다 먼저 본다 —
         * `57` 묶음이 `DataAccessResourceFailureException` 으로 번역되는 판에서도 무제한으로 안 샌다(`I17-12`).
         */
        fun backOffFor(exception: Exception): BackOff {
            val chain = generateSequence<Throwable>(exception) { it.cause }.toList()
            return when {
                chain.any { it is JacksonException } -> FixedBackOff(0, 0)
                exception.hitStatementLimit() -> boundedExponential()
                chain.any { it is DataAccessResourceFailureException || it is CannotCreateTransactionException } -> exponential()
                chain.any { it is TransientDataAccessException || it is TransactionException } -> boundedExponential()
                else -> FixedBackOff(RETRY_INTERVAL_MS, RETRY_COUNT)
            }
        }

        private fun exponential() = ExponentialBackOff(TRANSIENT_INITIAL_MS, TRANSIENT_MULTIPLIER).apply { maxInterval = TRANSIENT_MAX_MS }

        private fun boundedExponential() = exponential().apply { maxElapsedTime = TRANSIENT_MAX_ELAPSED_MS }
    }
}
