package com.projectticket.ticket.outbox

import org.slf4j.LoggerFactory

/**
 * 소비자 입구의 판 검사(`D11` 「버전」). 생산자가 판을 올리면 **두 판을 같이 낸다** — 그동안 소비자는 아는 판만 처리하고 모르는 판은 건너뛴다.
 *
 * **DLT 로 안 보낸다.** 두 판을 같이 내는 동안 새 판 사건마다 DLT 가 쌓여 진짜 실패가 묻힌다. 같은 사건의 아는 판이 따로 온다.
 * 로그는 식별자만(`D10`) — 페이로드는 안 찍는다.
 */
object EnvelopeVersions {

    private val log = LoggerFactory.getLogger(EnvelopeVersions::class.java)

    /** @return 이 소비자가 처리할 판이면 true. 아니면 INFO 한 줄을 남기고 false */
    fun accepts(envelope: OutboxRelay.Envelope, consumer: String): Boolean {
        if (envelope.version == EventType.SUPPORTED_VERSION) return true
        log.info(
            "모르는 판의 사건을 건너뛴다: consumer={}, event_id={}, type={}, version={}",
            consumer,
            envelope.eventId,
            envelope.type.code,
            envelope.version,
        )
        return false
    }
}
