package com.projectticket.ticket.notification

import com.projectticket.ticket.outbox.OutboxRelay
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 첫 아웃박스 소비자(26). 릴레이가 발행한 봉투를 받아 알림 행을 만든다.
 *
 * **예외를 삼킨다.** [NotificationStore] 의 `REQUIRES_NEW` 가 트랜잭션을 가르지만, 그 프록시가 예외를 위로 던지면
 * 릴레이가 그것을 받아 자기 트랜잭션을 롤백한다 — 경계와 예외 처리가 **둘 다** 있어야 「소비자가 발행자를 안 멈춘다」(`D11`)가 성립한다.
 *
 * 삼킨 것은 `WARN` 이다(`D10` — 지금은 도는데 이상한 것). 사건은 이미 발행 표시되므로 **그 알림은 다시 안 온다** —
 * 재시도·DLQ 는 29 가 든다. 지금 그것을 넣으면 소비자가 하나뿐인데 재시도 표가 하나 는다(`CLAUDE.md` 대전제 3).
 *
 * 빈이 둘인 이유는 자기 호출이다 — 같은 클래스 안에서 부르면 프록시를 안 지나 `REQUIRES_NEW` 가 통째로 무시된다(`stack.md`).
 */
@Component
class ReservationNotificationListener(private val store: NotificationStore) {

    private val log = LoggerFactory.getLogger(ReservationNotificationListener::class.java)

    @EventListener
    fun on(envelope: OutboxRelay.Envelope) {
        try {
            store.record(envelope)
        } catch (e: RuntimeException) {
            log.warn("알림을 못 만들었다 event_id={} type={} 이유={}", envelope.eventId, envelope.type.code, e.javaClass.simpleName, e)
        }
    }
}
