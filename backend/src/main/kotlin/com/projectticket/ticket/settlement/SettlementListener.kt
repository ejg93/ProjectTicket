package com.projectticket.ticket.settlement

import com.projectticket.ticket.outbox.OutboxRelay
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * **둘째 아웃박스 소비자다**(27). 첫째는 알림(26)이고, 둘이 된 것이 Kafka 를 들이는 근거가 된다(ADR 0001·28).
 *
 * 알림과 **서로 모른다**(`D11`) — 같은 사건을 각자 받고, 알림이 죽어도 정산은 간다. 그것이 이 구조의 값이다.
 * 예외를 삼키는 이유와 `REQUIRES_NEW` 의 이유는 [com.projectticket.ticket.notification.ReservationNotificationListener] 와 같다.
 */
@Component
class SettlementListener(private val store: SettlementStore) {

    private val log = LoggerFactory.getLogger(SettlementListener::class.java)

    @EventListener
    fun on(envelope: OutboxRelay.Envelope) {
        try {
            store.schedule(envelope)
        } catch (e: RuntimeException) {
            log.warn("정산을 예약 못 했다 event_id={} type={} 이유={}", envelope.eventId, envelope.type.code, e.javaClass.simpleName, e)
        }
    }
}
