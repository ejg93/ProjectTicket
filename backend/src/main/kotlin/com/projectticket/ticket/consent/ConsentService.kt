package com.projectticket.ticket.consent

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service

/**
 * 동의 항목을 읽고 동의 사건을 적는다.
 *
 * 상태를 안 고친다 — 동의도 한 행, 철회도 한 행이고 현재 상태는 `current_consent` 뷰가 만든다(`V3`).
 */
@Service
class ConsentService(private val jdbc: JdbcClient) {

    /**
     * 지금 효력이 있는 판만 코드별로 하나씩.
     *
     * `effective_at` 을 보는 이유는 개정판을 미리 넣어 둘 수 있어서다. 이 조건이 없으면 아직 시작 안 한 약관에 동의를 받는다.
     */
    fun currentItems(): List<ConsentItem> =
        jdbc.sql(
            """
            select distinct on (code)
                   consent_item_id, code, title, is_required as required, sort_no
              from consent_item
             where effective_at <= now()
             order by code, effective_at desc, version desc
            """,
        )
            .query(ConsentItem::class.java)
            .list()
            // `JdbcClient.list()` 의 원소 타입이 Java 에서 와서 플랫폼 타입이다. 행이 null 일 수 없는 조회라 걷어낸다.
            .filterNotNull()
            .sortedBy { it.sortNo }

    /**
     * 받은 동의가 항목 목록과 맞는지 본다. 안 맞으면 422 다 — 형식은 맞는데 값이 규칙에 안 맞는 자리다(`D5`).
     */
    fun verify(given: Map<String, Boolean>, items: List<ConsentItem>) {
        val byCode = items.associateBy { it.code }

        given.keys.firstOrNull { it !in byCode }?.let {
            throw TicketException(ErrorCode.UNKNOWN_CONSENT_ITEM, "모르는 동의 항목이다: $it")
        }

        items.filter { it.required }.firstOrNull { given[it.code] != true }?.let {
            throw TicketException(ErrorCode.REQUIRED_CONSENT_MISSING, "필수 동의 항목이다: ${it.code}")
        }
    }

    /**
     * **담긴 것만** 적는다. 안 담긴 선택 항목은 행이 안 생긴다 — 거부(false 행)와 무응답(행 없음)을 갈라 두려는 것이다.
     */
    fun record(accountId: Long, given: Map<String, Boolean>, items: List<ConsentItem>, source: String, actorIp: String?) {
        val idByCode = items.associate { it.code to it.consentItemId }

        given.forEach { (code, granted) ->
            jdbc.sql(
                """
                insert into account_consent (account_id, consent_item_id, granted, source, acted_ip)
                values (:accountId, :itemId, :granted, :source, cast(:actorIp as inet))
                """,
            )
                .param("accountId", accountId)
                .param("itemId", idByCode.getValue(code))
                .param("granted", granted)
                .param("source", source)
                .param("actorIp", actorIp)
                .update()
        }
    }

    /**
     * 불리언의 `is_` 는 Kotlin 에서 뗀다(`D15`). 조회 SQL 이 `is_required as required` 로 별칭을 주는 이유는
     * `query(ConsentItem::class.java)` 가 컬럼명으로 생성자 인자를 맞추기 때문이다.
     */
    data class ConsentItem(val consentItemId: Long, val code: String, val title: String, val required: Boolean, val sortNo: Int)
}
