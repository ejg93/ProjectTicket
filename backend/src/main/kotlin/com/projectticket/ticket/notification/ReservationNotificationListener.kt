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
 * **예외를 삼킨다.** [NotificationStore] 의 `REQUIRES_NEW` 가 트랜잭션을 가르지만, 그 프록시가 예외를 위로 던지면
 * 릴레이가 그것을 받아 자기 트랜잭션을 롤백한다 — 경계와 예외 처리가 **둘 다** 있어야 「소비자가 발행자를 안 멈춘다」(`D11`)가 성립한다.
 *
 * 삼킨 것은 `WARN` 이다(`D10` — 지금은 도는데 이상한 것). 오프셋이 이미 넘어가서 **그 알림은 다시 안 온다** —
 * 재시도·DLQ 는 29 가 든다. 지금 그것을 넣으면 소비자가 하나뿐인데 재시도 표가 하나 는다(`CLAUDE.md` 대전제 3).
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
        val envelope = json.readValue(message, OutboxRelay.Envelope::class.java)
        try {
            store.record(envelope)
        } catch (e: RuntimeException) {
            log.warn("알림을 못 만들었다 event_id={} type={} 이유={}", envelope.eventId, envelope.type.code, e.javaClass.simpleName, e)
        }
    }

    companion object {
        const val GROUP = "notification"
    }
}
