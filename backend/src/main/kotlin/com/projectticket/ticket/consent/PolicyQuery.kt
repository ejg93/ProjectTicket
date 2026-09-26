package com.projectticket.ticket.consent

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * 공개하는 방침 문서(`39-3a`, 개인정보 보호법 제30조)의 지금 판. 동의 항목([ConsentService.currentItem])과 판을 고르는 규칙이 같다 —
 * `effective_at <= now()` 인 최신. 개정판을 미리 넣어 둘 수 있어서다.
 */
@Component
class PolicyQuery(private val jdbc: JdbcClient) {

    data class PolicyDocument(val code: String, val title: String, val version: Int, val effectiveAt: OffsetDateTime, val body: String)

    fun current(code: String): PolicyDocument =
        jdbc.sql(
            """
            select code, title, version, effective_at, body
              from policy_document
             where code = :code and effective_at <= now()
             order by effective_at desc, version desc
             limit 1
            """,
        )
            .param("code", code)
            .query(PolicyDocument::class.java)
            .optional()
            .orElseThrow { TicketException(ErrorCode.POLICY_NOT_FOUND, "그런 방침 문서가 없다: $code") }
}
