package com.projectticket.ticket.notification

import com.projectticket.ticket.outbox.EventTopics
import com.projectticket.ticket.outbox.OutboxRelay
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * 첫 아웃박스 소비자(26). 릴레이가 발행한 봉투를 받아 알림 행을 만든다.
 *
 * **예외를 안 삼킨다**(29 부터). 28 까지는 삼켰는데 — 그때는 재시도할 자리가 없어서 삼키지 않으면 발행자가 멈췄다 —
 * 이제 브로커가 사이에 있어서 던지는 것이 맞다: `ConsumerErrorHandling` 이 세 번 다시 시도하고 그래도 안 되면 `.DLT` 로 보낸다.
 * 「소비자 하나의 결함이 발행자를 안 멈춘다」(`D11`)는 이제 그 손잡이가 지킨다.
 *
 * 다시 시도해도 되는 이유는 **멱등이라서**다 — `(event_id, account_id)` 유일 제약이 두 번째 시도를 0행으로 끝낸다.
 *
 * 빈이 둘인 이유는 자기 호출이다 — 같은 클래스 안에서 부르면 프록시를 안 지나 `REQUIRES_NEW` 가 통째로 무시된다(`stack.md`).
 */
@Component
class ReservationNotificationListener(
    private val store: NotificationStore,
    private val json: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(ReservationNotificationListener::class.java)

    /** 그룹이 정산과 다르다(ADR 0007) — 같은 그룹이면 사건 하나를 둘 중 하나만 받는다 */
    @KafkaListener(topics = [EventTopics.RESERVATION, EventTopics.PERFORMANCE], groupId = GROUP)
    fun on(message: String) {
        // **던진다**(29). 삼키면 재시도도 DLT 도 안 돈다 — 그 사건은 조용히 사라진다.
        store.record(json.readValue(message, OutboxRelay.Envelope::class.java))
    }

    companion object {
        const val GROUP = "notification"
    }
}
