package com.projectticket.ticket.settlement

import com.projectticket.ticket.outbox.EventTopics
import com.projectticket.ticket.outbox.OutboxRelay
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * **둘째 아웃박스 소비자다**(27). 첫째는 알림(26)이고, 둘이 된 것이 Kafka 를 들이는 근거가 된다(ADR 0001·28).
 *
 * 알림과 **서로 모른다**(`D11`) — 같은 사건을 각자 받고, 알림이 죽어도 정산은 간다. 그것이 이 구조의 값이다.
 * 실패를 던지는 이유와 `REQUIRES_NEW` 의 이유는 [com.projectticket.ticket.notification.ReservationNotificationListener] 와 같다.
 */
@Component
class SettlementListener(
    private val store: SettlementStore,
    private val json: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(SettlementListener::class.java)

    /**
     * **그룹이 알림과 다르다**(ADR 0007) — 같은 사건을 둘 다 받으려면 그래야 한다. 같은 그룹이면 하나만 받는다.
     * 두 토픽을 다 구독한다: 정산은 회차 사건(종료·취소)과 예매 사건을 같이 본다.
     */
    @KafkaListener(topics = [EventTopics.RESERVATION, EventTopics.PERFORMANCE], groupId = GROUP)
    fun on(message: String) {
        // 던진다(29) — 재시도·DLT 는 `ConsumerErrorHandling` 이 든다. 멱등은 `performance_id` 유일 제약이다
        store.schedule(json.readValue(message, OutboxRelay.Envelope::class.java))
    }

    companion object {
        /** 소비자 그룹. 오프셋이 이 이름으로 브로커에 남는다 */
        const val GROUP = "settlement"
    }
}
