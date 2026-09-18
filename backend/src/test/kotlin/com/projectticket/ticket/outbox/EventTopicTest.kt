package com.projectticket.ticket.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 토픽 이름이 두 벌인 것을 막는다(`EventTopics` 의 강제 지점, ADR 0007).
 *
 * **두 벌이 생기는 이유는 `@KafkaListener` 다.** 어노테이션의 `topics` 는 컴파일 상수여야 해서 [EventTopics.RESERVATION] 같은
 * 상수를 쓰고, 릴레이는 집합체 타입으로 골라야 해서 [EventTopics.of] 를 쓴다. 둘이 갈리면 **아무 오류 없이**
 * 발행과 구독이 다른 토픽을 보고, 사건은 조용히 안 도착한다 — 컨테이너를 띄워야 보이는 결함이라 여기서 잡는다.
 *
 * DB 도 브로커도 안 쓴다. 빠른 레인이다.
 */
class EventTopicTest {

    @Test
    fun every_aggregate_type_maps_to_a_declared_constant() {
        val declared = setOf(EventTopics.RESERVATION, EventTopics.PERFORMANCE)

        // 집합체가 하나 늘면 상수도 같이 늘어야 한다. 안 늘리면 여기서 걸린다.
        assertThat(AggregateType.entries.map(EventTopics::of)).containsExactlyInAnyOrderElementsOf(declared)
    }

    @Test
    fun the_constants_carry_the_aggregate_code() {
        assertThat(EventTopics.of(AggregateType.RESERVATION)).isEqualTo(EventTopics.RESERVATION)
        assertThat(EventTopics.of(AggregateType.PERFORMANCE)).isEqualTo(EventTopics.PERFORMANCE)
    }

    @Test
    fun the_key_is_the_aggregate_id_as_text() {
        // 키가 곧 파티션이다(`D11`). 형식이 바뀌면 같은 예매의 사건이 흩어져 순서가 깨진다.
        assertThat(EventTopics.keyOf(990_001L)).isEqualTo("990001")
    }
}
