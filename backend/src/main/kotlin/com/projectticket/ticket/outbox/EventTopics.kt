package com.projectticket.ticket.outbox

/**
 * 토픽 이름과 파티션 키(ADR 0007). **집합체 타입마다 토픽 하나**다.
 *
 * 사건 타입마다 토픽을 만들면 한 예매의 확정과 취소가 **다른 토픽에 흩어져 순서를 보장할 방법이 없다.**
 * 토픽 하나로 뭉치면 소비자가 남의 사건까지 전부 읽는다. 그 사이가 집합체 타입이다.
 *
 * 키가 `aggregate_id` 라 **그 예매의 사건은 한 파티션**으로 가고, 파티션 안에서는 순서가 지켜진다(`D11`).
 *
 * 상수와 [of] 가 같은 이름을 내는지는 `EventTopicTest` 가 잰다 — `@KafkaListener` 의 `topics` 는 상수여야 해서
 * 두 벌이 생기는 자리고, 두 벌은 갈린다.
 */
object EventTopics {

    const val RESERVATION = "ticket.reservation"
    const val PERFORMANCE = "ticket.performance"

    /** 집합체 타입 → 토픽. 새 집합체가 생기면 상수도 같이 는다 */
    fun of(aggregateType: AggregateType): String = "$PREFIX${aggregateType.code}"

    /** 키는 문자열이다 — 브로커가 그 바이트를 해시해서 파티션을 고른다 */
    fun keyOf(aggregateId: Long): String = aggregateId.toString()

    private const val PREFIX = "ticket."
}
