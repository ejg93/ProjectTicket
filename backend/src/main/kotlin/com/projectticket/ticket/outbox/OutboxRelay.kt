package com.projectticket.ticket.outbox

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
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
 *
 * **한 회가 한 트랜잭션이다.** 배치 중간에 던지면 그 회차가 통째로 롤백돼 같은 배치를 다시 집는다 — 소비자는 멱등이라(`event_id`) 눈에 보이는 중복이 없지만,
 * **계속 실패하는 사건 하나가 뒤를 막는다.** 그것을 건너뛰는 자리가 재시도·DLQ 고 29 가 든다.
 * **발행은 at-least-once 다.** 발행하고 `published_at` 을 찍기 전에 죽으면 같은 사건이 다시 나간다.
 * 트랜잭션을 걸어도 마찬가지다 — 발행은 DB 밖의 일이라 커밋과 같이 되돌아가지 않는다. 그래서 소비자가 `event_id` 로 멱등이어야 한다(29).
 */
@Component
class OutboxRelay(
    private val jdbc: JdbcClient,
    private val json: ObjectMapper,
    private val events: ApplicationEventPublisher,
) {

    private val log = LoggerFactory.getLogger(OutboxRelay::class.java)

    /**
     * 발행되는 것. 지금은 Spring 이벤트로 나가고 28 이 Kafka 로 갈아끼운다 — **소비자는 이 타입만 알면 된다**(`aggregate_*` 가 파티션 키가 된다).
     *
     * @param payload 카탈로그가 정한 필드(`D11`). 소비자는 이 안의 식별자로 표를 다시 읽는다 — 사건은 「무슨 일이 났나」고 지금 상태는 표가 안다
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
            select outbox_id, event_id, type, version, occurred_at, aggregate_type, aggregate_id, payload::text as payload
              from outbox
             where published_at is null
             order by outbox_id
             limit :batch
               for update skip locked
            """,
        ).param("batch", BATCH_SIZE).query(Row::class.java).list().filterNotNull()

        if (rows.isEmpty()) {
            log.debug("아웃박스 릴레이 — 보낼 것 없음")
            return 0
        }

        rows.forEach { events.publishEvent(envelopeOf(it)) }

        jdbc.sql("update outbox set published_at = now() where outbox_id in (:ids)")
            .param("ids", rows.map { it.outboxId })
            .update()

        log.info("아웃박스 릴레이 발행={}건", rows.size)
        return rows.size
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
    )

    companion object {
        /** 폴링 주기. 스윕(30초)보다 짧은 이유는 사람이 메일을 기다리는 자리라서다 */
        const val RELAY_INTERVAL = "PT1S"

        /** 한 회에 가져가는 수. 트랜잭션이 길어지면 그동안 잡은 행을 남이 못 가져간다 */
        const val BATCH_SIZE = 100
    }
}
