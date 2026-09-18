package com.projectticket.ticket.outbox

import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper

/**
 * 안 나간 사건을 **브로커를 건너뛰고** 소비자에게 바로 건넨다. 테스트 전용이다(28).
 *
 * 왜 필요한가: 28 뒤로 소비는 Kafka 소비자 스레드에서 **자기 트랜잭션**으로 돈다.
 * 롤백 레인 테스트(`PostgresTestBase`)가 만든 행은 아직 커밋 전이라 그 소비자에게 안 보인다 — 이 자리를 알고 있다(`stack.md`).
 *
 * 그래서 층을 갈랐다:
 *
 * | 무엇을 재나 | 어디서 |
 * |---|---|
 * | 릴레이가 토픽·키·봉투를 맞게 보내나, 그룹이 다른 소비자가 각자 받나 | `KafkaRelayTest`(커밋 레인, 진짜 브로커) |
 * | 사건이 오면 어떤 행이 생기나(수신자·멱등·정산 예약) | 이 도우미로 소비자 로직을 직접 부른다 |
 *
 * **발행 표시는 릴레이와 같게 남긴다** — 두 번 호출하면 두 번째는 집을 것이 없어야 멱등 테스트가 뜻을 갖는다.
 */
class PendingEvents(
    private val jdbc: JdbcClient,
    private val json: ObjectMapper,
) {

    /** @return 건넨 사건 수 */
    fun deliver(consume: (OutboxRelay.Envelope) -> Unit): Int {
        val rows = jdbc.sql(
            """
            select outbox_id, event_id, type, version, occurred_at, aggregate_type, aggregate_id, payload::text as payload
              from outbox
             where published_at is null
             order by outbox_id
            """,
        ).query(OutboxRelay.Row::class.java).list().filterNotNull()

        if (rows.isEmpty()) return 0

        rows.forEach { row ->
            val type = EventType.of(row.type)
            @Suppress("UNCHECKED_CAST")
            val payload = json.readValue(row.payload, Map::class.java) as Map<String, Any?>
            consume(
                OutboxRelay.Envelope(
                    eventId = row.eventId,
                    type = type,
                    version = row.version,
                    occurredAt = row.occurredAt,
                    aggregateType = type.aggregateType,
                    aggregateId = row.aggregateId,
                    payload = payload,
                ),
            )
        }

        jdbc.sql("update outbox set published_at = now() where outbox_id in (:ids)")
            .param("ids", rows.map { it.outboxId })
            .update()
        return rows.size
    }
}
