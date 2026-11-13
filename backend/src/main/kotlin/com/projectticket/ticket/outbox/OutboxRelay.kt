package com.projectticket.ticket.outbox

import org.apache.kafka.common.errors.RetriableException
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 안 나간 사건을 가져가 발행한다(`D11` 「발행」).
 *
 * **`for update skip locked` 로 가져간다.** 인스턴스 셋이 같은 행을 두 번 안 민다 — 남이 잡은 행은 기다리지 않고 건너뛴다.
 * 스케줄러 락(33)이 필요 없는 이유가 이것이다: 스윕과 달리 여러 대가 **동시에 서로 다른 행**을 밀면 그만큼 빨라진다.
 *
 * **고정 주기 폴링이다**(사용자 선택). 커밋 뒤 즉시 깨우는 길도 있지만 소비자가 메일·정산이라 1초가 문제되는 자리가 없고,
 * 코드가 한 겹 적다. 값이 오르면(좌석도가 사건을 타는 날) 그때 즉시 깨우기를 얹는다 — 폴링은 그대로 백업이 된다.
 *
 * **한 회가 한 트랜잭션이고, 행마다 격리한다**(`I17-4`). 한 행이 던져도(봉투를 못 만든다·`RecordTooLargeException`) 그 행만
 * `attempts + 1`·`last_error`·`next_attempt_at`(1초부터 두 배, 최대 5분)을 적고 다음 행으로 간다. 10회째에 주차한다(ERROR 로그) —
 * 푸는 것은 사람이다(`event-catalog.md` 「발행」). **일시 오류는 안 센다** — 원인 사슬에 Kafka `RetriableException` 이 있으면
 * 브로커 탓이라 그 회를 멈추고 다음 회에 그대로 다시 간다. 세면 브로커 장애 13분에 모든 사건이 주차된다.
 *
 * **집합체마다 맨 앞 미발행 행만 잡는다.** 같은 집합체의 사건이 순서를 지킨다(`event-catalog.md`) — 독 행 뒤의 같은 집합체 사건이
 * 서고, `skip locked` 로 두 파드가 같은 집합체의 앞뒤 행을 나눠 잡아 순서가 뒤집히던 틈도 같이 닫힌다. 값은 한 집합체에 사건이
 * 몰리면 회마다 하나씩 나가는 것이다 — 예매 하나의 사건은 많아야 둘이다.
 *
 * **발행은 at-least-once 다.** 발행하고 `published_at` 을 찍기 전에 죽으면 같은 사건이 다시 나간다.
 * 트랜잭션을 걸어도 마찬가지다 — 발행은 DB 밖의 일이라 커밋과 같이 되돌아가지 않는다. 그래서 소비자가 `event_id` 로 멱등이어야 한다(29).
 * **한 건이 막히는 시간에 상한이 있다**(`I17-3`) — `send()` 가 `max.block.ms` 5초, `join()` 이 `delivery.timeout.ms` 15초다.
 * **한 회에도 상한이 있다** — [ROUND_BUDGET] 을 넘으면 남은 행은 안 보내고 다음 회로 넘긴다. 잡은 행을 쥔 트랜잭션이 길어지지 않게 한다.
 * 스케줄러 풀이 4 라 선점 만료·대기열 입장이 이 회를 안 기다린다.
 */
@Component
class OutboxRelay(
    private val jdbc: JdbcClient,
    private val json: ObjectMapper,
    private val kafka: KafkaTemplate<String, String>,
) {

    private val log = LoggerFactory.getLogger(OutboxRelay::class.java)

    /**
     * 발행되는 것. Kafka 로 나간다(28, ADR 0007) — 값은 이 봉투의 JSON 이고 키는 `aggregate_id` 다.
     *
     * [payload] 는 카탈로그가 정한 필드다(`D11`). 소비자는 이 안의 식별자로 표를 다시 읽는다 — 사건은 「무슨 일이 났나」고 지금 상태는 표가 안다
     */
    data class Envelope(
        val eventId: UUID,
        val type: EventType,
        val version: Int,
        val occurredAt: OffsetDateTime,
        val aggregateType: AggregateType,
        val aggregateId: Long,
        val payload: Map<String, Any?>,
    )

    /** @return 발행한 사건 수 */
    @Scheduled(fixedDelayString = RELAY_INTERVAL)
    @Transactional
    fun relay(): Int {
        val rows = jdbc.sql(
            """
            select o.outbox_id, o.event_id, o.type, o.version, o.occurred_at, o.aggregate_type, o.aggregate_id,
                   o.payload::text as payload, o.attempts
              from outbox o
             where o.published_at is null
               and o.attempts < :parkAt
               and (o.next_attempt_at is null or o.next_attempt_at <= now())
               and not exists (
                   select 1 from outbox ahead
                    where ahead.aggregate_type = o.aggregate_type and ahead.aggregate_id = o.aggregate_id
                      and ahead.published_at is null and ahead.outbox_id < o.outbox_id)
             order by o.outbox_id
             limit :batch
               for update skip locked
            """,
        ).param("batch", BATCH_SIZE).param("parkAt", PARK_AFTER).query(Row::class.java).list().filterNotNull()

        if (rows.isEmpty()) {
            log.debug("아웃박스 릴레이 — 보낼 것 없음")
            return 0
        }

        // **보내고 나서 표시한다.** 표시부터 하면 보내기가 실패했을 때 그 사건이 영영 안 나간다 —
        // 반대로 보내고 죽으면 같은 사건이 다시 나가고, 그것은 소비자가 멱등이라 받아 낸다(at-least-once, `D11`).
        // 시퀀스라 앞에서부터 하나씩 돈다 — 시간 예산이 다 되거나 브로커가 일시 오류를 내면 남은 행은 손대지 않고 다음 회다.
        val started = System.nanoTime()
        val sent = rows.asSequence()
            .takeWhile { System.nanoTime() - started < ROUND_BUDGET.toNanos() }
            .map { row -> row to publish(row) }
            .takeWhile { (_, outcome) -> outcome != Outcome.HALTED }
            .filter { (_, outcome) -> outcome == Outcome.SENT }
            .map { (row, _) -> row.outboxId }
            .toList()

        if (sent.isNotEmpty()) {
            jdbc.sql("update outbox set published_at = now() where outbox_id in (:ids)")
                .param("ids", sent)
                .update()
        }

        log.info("아웃박스 릴레이 발행={}건 잡은={}건", sent.size, rows.size)
        return sent.size
    }

    /** 한 행의 결과. [HALTED] 는 브로커 일시 오류라 이 회를 멈춘다 — 행은 안 센다 */
    private enum class Outcome { SENT, FAILED, HALTED }

    private fun publish(row: Row): Outcome =
        try {
            send(envelopeOf(row))
            Outcome.SENT
        } catch (e: RuntimeException) {
            val error = rootOf(e).javaClass.name
            if (isTransient(e)) {
                log.warn("아웃박스 릴레이 — 브로커 일시 오류, 이 회를 멈춘다 outbox_id={} error={}", row.outboxId, error)
                Outcome.HALTED
            } else {
                recordFailure(row, error)
                Outcome.FAILED
            }
        }

    private fun send(envelope: Envelope) {
        kafka.send(
            EventTopics.of(envelope.aggregateType),
            EventTopics.keyOf(envelope.aggregateId),
            json.writeValueAsString(envelope),
        ).join()
    }

    /** 사건 탓의 실패를 센다. 다음 시도는 `2^attempts` 초 뒤(최대 5분)이고 [PARK_AFTER] 째가 주차다. [error] 는 예외 클래스 이름만(`D10`) */
    private fun recordFailure(row: Row, error: String) {
        jdbc.sql(
            """
            update outbox
               set attempts = attempts + 1,
                   last_error = :error,
                   next_attempt_at = now() + make_interval(secs => least(power(2, attempts), :maxBackoff))
             where outbox_id = :id
            """,
        )
            .param("error", error)
            .param("maxBackoff", MAX_BACKOFF_SECONDS)
            .param("id", row.outboxId)
            .update()
        if (row.attempts + 1 >= PARK_AFTER) {
            // 사람이 볼 것이다 — 이 집합체의 뒤 사건도 같이 서 있다. 페이로드는 안 찍는다(`D10`).
            log.error("아웃박스 사건 주차 outbox_id={} type={} aggregate_id={} error={}", row.outboxId, row.type, row.aggregateId, error)
        } else {
            log.warn("아웃박스 사건 발행 실패 outbox_id={} attempts={} error={}", row.outboxId, row.attempts + 1, error)
        }
    }

    private fun envelopeOf(row: Row): Envelope {
        val type = EventType.of(row.type)
        @Suppress("UNCHECKED_CAST")
        val payload = json.readValue(row.payload, Map::class.java) as Map<String, Any?>
        return Envelope(
            eventId = row.eventId,
            type = type,
            version = row.version,
            occurredAt = row.occurredAt,
            aggregateType = type.aggregateType,
            aggregateId = row.aggregateId,
            payload = payload,
        )
    }

    data class Row(
        val outboxId: Long,
        val eventId: UUID,
        val type: String,
        val version: Int,
        val occurredAt: OffsetDateTime,
        val aggregateType: String,
        val aggregateId: Long,
        val payload: String,
        val attempts: Int,
    )

    companion object {
        /** 폴링 주기. 스윕(30초)보다 짧은 이유는 사람이 메일을 기다리는 자리라서다 */
        const val RELAY_INTERVAL = "PT1S"

        /** 한 회에 가져가는 수. 트랜잭션이 길어지면 그동안 잡은 행을 남이 못 가져간다 */
        const val BATCH_SIZE = 100

        /** 이만큼 실패하면 주차한다. 1초부터 두 배·최대 5분이라 약 13분이다 */
        const val PARK_AFTER = 10

        /** 지수 대기의 상한(초) */
        const val MAX_BACKOFF_SECONDS = 300

        /** 한 회의 시간 상한. 넘으면 남은 행은 다음 회다 — 잡은 행을 쥔 트랜잭션이 길어지지 않게 한다 */
        val ROUND_BUDGET: Duration = Duration.ofSeconds(10)

        /** 브로커 탓이라 사건을 세지 않는 실패. 원인 사슬에 Kafka `RetriableException` 이 있다 */
        fun isTransient(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { it is RetriableException }

        private fun rootOf(e: Throwable): Throwable = generateSequence(e) { it.cause }.last()
    }
}
