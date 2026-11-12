package com.projectticket.ticket.outbox

import org.slf4j.LoggerFactory

/**
 * 소비자가 사건 하나를 처리했다는 INFO 한 줄(`I16-7`, `D10` 「비동기 경계」).
 *
 * `traceparent` 는 아웃박스에서 끊긴다 — 추적 열이 없고 릴레이가 헤더 없이 보낸다. 요청 로그(`reservation_id`)에서
 * `outbox`(`aggregate_id` → `event_id`)를 거쳐 소비자 로그로 건너오는 다리가 이 줄이다. 키 이름은 [EnvelopeVersions] 의
 * 건너뜀 줄과 같다 — 한 키로 두 줄을 같이 찾는다. 식별자만 찍는다(`D10`) — 페이로드는 안 찍는다.
 */
object ConsumerLog {

    private val log = LoggerFactory.getLogger(ConsumerLog::class.java)

    fun handled(envelope: OutboxRelay.Envelope, consumer: String) {
        log.info(
            "사건을 처리했다: consumer={}, event_id={}, type={}, aggregate_id={}",
            consumer,
            envelope.eventId,
            envelope.type.code,
            envelope.aggregateId,
        )
    }
}
