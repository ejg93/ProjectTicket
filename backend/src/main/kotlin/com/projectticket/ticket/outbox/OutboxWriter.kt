package com.projectticket.ticket.outbox

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 사건 행을 넣는 유일한 입구(`D11` 「발행」). 부르는 쪽의 트랜잭션에 **반드시 참여한다.**
 *
 * `Propagation.MANDATORY` 가 이 클래스의 핵심이다 — 트랜잭션 없이 부르면 그 자리에서 예외다.
 * 「확정 없이 사건 없고, 사건 없이 확정 없다」의 절반을 프레임워크가 막는 것이고, 나머지 절반(롤백이 사건도 되돌린다)은 트랜잭션이 공짜로 준다.
 * 이것이 없으면 누군가 `@Transactional` 밖에서 부르는 날 사건만 남은 행이 생기고, 그 소비자는 없는 예매에 메일을 보낸다.
 */
@Component
class OutboxWriter(private val jdbc: JdbcClient, private val json: ObjectMapper) {

    /**
     * @param aggregateId 그 사건이 일어난 집합체. 파티션 키의 뒷자리(28)
     * @param payload 식별자와 **그때의 값**. 개인정보를 안 담는다(`D11` 「페이로드 규칙」) — 사건은 브로커·DLQ·로그에 남는다
     * @return 만든 `event_id`. 소비자 멱등의 키고, 테스트가 이 값으로 행을 찾는다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun append(type: EventType, aggregateId: Long, payload: Map<String, Any?>): UUID {
        val eventId = UUID.randomUUID()
        jdbc.sql(
            """
            insert into outbox (event_id, type, aggregate_type, aggregate_id, payload)
            values (:eventId, :type, :aggregateType, :aggregateId, :payload::jsonb)
            """,
        )
            .param("eventId", eventId)
            .param("type", type.code)
            .param("aggregateType", type.aggregateType.code)
            .param("aggregateId", aggregateId)
            .param("payload", json.writeValueAsString(payload))
            .update()
        return eventId
    }
}
