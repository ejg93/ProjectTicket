package com.projectticket.ticket.audit

import com.projectticket.ticket.audit.AuditLog.Target
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * 트랜잭션 경계만 가르는 자리. [AuditLog] 와 나뉜 이유는 **자기 호출로는 전파가 안 걸려서**다 —
 * 한 클래스 안에서 `detached()` 를 부르면 프록시를 안 지나 `REQUIRES_NEW` 가 무시된다.
 */
@Component
class AuditLogWriter(private val jdbc: JdbcClient, private val objectMapper: ObjectMapper) {

    /** 부르는 쪽 트랜잭션이 롤백돼도 남는다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun detached(eventType: String, actorAccountId: Long?, target: Target, detail: Map<String, Any?>) =
        insert(eventType, actorAccountId, target, detail)

    /** 부르는 쪽 트랜잭션에 얹는다. 없으면 이 문장 하나가 트랜잭션이다 */
    @Transactional
    fun joined(eventType: String, actorAccountId: Long?, target: Target, detail: Map<String, Any?>) =
        insert(eventType, actorAccountId, target, detail)

    private fun insert(eventType: String, actorAccountId: Long?, target: Target, detail: Map<String, Any?>) {
        jdbc.sql(
            """
            insert into audit_log (event_type, actor_account_id, target_type, target_id, detail)
            values (:eventType, :actorAccountId, :targetType, :targetId, cast(:detail as jsonb))
            """,
        )
            .param("eventType", eventType)
            .param("actorAccountId", actorAccountId)
            .param("targetType", target.type)
            .param("targetId", target.id)
            .param("detail", objectMapper.writeValueAsString(detail))
            .update()
    }
}
