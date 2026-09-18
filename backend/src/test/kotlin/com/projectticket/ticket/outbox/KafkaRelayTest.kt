package com.projectticket.ticket.outbox

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.Waits
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import tools.jackson.databind.ObjectMapper

/**
 * 릴레이가 Kafka 로 무엇을 보내나(28의 닫힘 조건, ADR 0007).
 *
 * **커밋 레인이다.** 소비자는 다른 스레드에서 자기 트랜잭션으로 표를 읽으므로, 롤백 바탕의 미커밋 행은 안 보인다(`stack.md`).
 *
 * 재는 것 셋 — 봉투가 그대로 가나, **키가 `aggregate_id` 인가**(같은 예매의 사건이 한 파티션으로 가는 근거),
 * 그리고 **그룹이 다른 소비자가 같은 사건을 각자 받나**(그룹을 가른 이유가 그것이다).
 */
@Import(KafkaRelayTest.Spy::class)
class KafkaRelayTest : ConcurrencyTestBase() {

    @Autowired lateinit var relay: OutboxRelay
    @Autowired lateinit var spy: Spy

    @BeforeEach
    fun clearSpy() {
        jdbc.sql("delete from outbox where aggregate_id = :id").param("id", AGGREGATE).update()
        spy.received.clear()
    }

    @Test
    fun the_envelope_arrives_with_the_aggregate_as_key() {
        val eventId = insertEvent()

        assertThat(relay.relay()).isPositive()

        // 파티션이 셋이라(29) 소비자가 붙는 데 시간이 더 걸린다. 시한을 넉넉히 둔다.
        val delivered = Waits.value("봉투가 도착", Duration.ofSeconds(40)) {
            spy.received.firstOrNull { it.envelope.eventId == eventId }
        }
        assertThat(delivered.envelope.type).isEqualTo(EventType.RESERVATION_RESERVED)
        assertThat(delivered.envelope.aggregateType).isEqualTo(AggregateType.RESERVATION)
        assertThat(delivered.envelope.payload["reservation_id"]).isEqualTo(AGGREGATE.toInt())
        // 키가 곧 파티션이다. 키가 없거나 다른 값이면 한 예매의 사건이 흩어져 순서가 깨진다(`D11`).
        assertThat(delivered.key).isEqualTo(AGGREGATE.toString())
        assertThat(delivered.topic).isEqualTo(EventTopics.RESERVATION)
    }

    @Test
    fun a_second_group_gets_the_same_event() {
        val eventId = insertEvent()
        relay.relay()

        // 같은 그룹이면 사건 하나를 둘 중 하나만 받는다. 알림과 정산이 **각자** 받으려면 그룹이 달라야 한다(ADR 0007).
        Waits.until("첫 그룹이 받는다", Duration.ofSeconds(40)) { spy.received.any { it.envelope.eventId == eventId } }
        Waits.until("둘째 그룹도 받는다", Duration.ofSeconds(40)) { spy.otherGroup.any { it.eventId == eventId } }
    }

    private fun insertEvent(): UUID {
        val eventId = UUID.randomUUID()
        jdbc.sql(
            """
            insert into outbox (event_id, type, aggregate_type, aggregate_id, payload)
            values (:id, 'reservation.reserved', 'reservation', :aggregate, :payload::jsonb)
            """,
        )
            .param("id", eventId)
            .param("aggregate", AGGREGATE)
            .param("payload", """{"reservation_id": $AGGREGATE}""")
            .update()
        return eventId
    }

    /**
     * 테스트 소비자 둘. 그룹이 달라서 **같은 사건을 각자 받는다** — 진짜 소비자(알림·정산)와 같은 모양이다.
     *
     * `@TestConfiguration` + `@Import` 로 넣는다 — 테스트 소스는 컴포넌트 스캔 대상이 아니다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    class Spy(private val json: ObjectMapper) {

        val received: MutableList<Delivered> = CopyOnWriteArrayList()
        val otherGroup: MutableList<OutboxRelay.Envelope> = CopyOnWriteArrayList()

        @KafkaListener(topics = [EventTopics.RESERVATION], groupId = "kafka-relay-test-a")
        fun first(
            message: String,
            @Header(KafkaHeaders.RECEIVED_KEY) key: String?,
            @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
        ) {
            received += Delivered(json.readValue(message, OutboxRelay.Envelope::class.java), key, topic)
        }

        @KafkaListener(topics = [EventTopics.RESERVATION], groupId = "kafka-relay-test-b")
        fun second(message: String) {
            otherGroup += json.readValue(message, OutboxRelay.Envelope::class.java)
        }
    }

    data class Delivered(val envelope: OutboxRelay.Envelope, val key: String?, val topic: String)

    private companion object {
        /** 다른 테스트의 사건과 안 섞이게 이 테스트만의 집합체 id 를 쓴다 */
        const val AGGREGATE = 990_001L
    }
}
