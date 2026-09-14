package com.projectticket.ticket.audit

import org.springframework.stereotype.Component

/**
 * 감사 기록의 입구. 서비스는 이것만 부르고 트랜잭션 경계는 모른다.
 *
 * 무엇을 남기는지는 ADR 0003 이 정했다 — 돈·권한·상태가 바뀌는 것만이다.
 * 남기지 말 것은 `D10` 이 정한다: 개인정보는 식별자만이고 비밀번호·본문은 안 담는다.
 */
@Component
class AuditLog(private val writer: AuditLogWriter) {

    /**
     * 이 기록이 부르는 쪽 트랜잭션과 운명을 같이하나.
     *
     * **이 구분이 이 클래스의 존재 이유다.** 로그인 실패는 그 요청이 예외로 끝나는데, 같은 트랜잭션에 담으면
     * 롤백과 함께 사라진다 — 정작 남겨야 할 것이 실패한 시도다.
     */
    enum class Kind {
        /** 별도 트랜잭션. 부르는 쪽이 실패해도 남는다. 거부·실패한 시도가 여기다 */
        ATTEMPT,

        /** 같은 트랜잭션. 일이 실제로 일어났을 때만 남아야 하는 것이다 */
        OUTCOME,
    }

    fun record(
        kind: Kind,
        eventType: String,
        actorAccountId: Long?,
        target: Target = Target.none(),
        detail: Map<String, Any?> = emptyMap(),
    ) = when (kind) {
        Kind.ATTEMPT -> writer.detached(eventType, actorAccountId, target, detail)
        Kind.OUTCOME -> writer.joined(eventType, actorAccountId, target, detail)
    }

    /** 무엇에 대해 한 일인가. 자원이 없는 사건이면 [none] */
    data class Target(val type: String?, val id: Long?) {
        companion object {
            fun none() = Target(null, null)
            fun of(type: String, id: Long) = Target(type, id)
        }
    }
}
