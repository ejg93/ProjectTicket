package com.projectticket.ticket.consent

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

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
     * 항목 하나의 지금 판과 본문(`39-1`). 약관은 `body`(마크다운), 개인정보 수집은 정형 넷(목적·항목·보유 기간·거부 불이익)을 든다 —
     * 개인정보 보호법 제15조 제2항이 고지할 넷을 정해서 `V3` 가 칸으로 뒀다. 목록([currentItems])은 이것을 안 싣는다 — 행을 가볍게(`D5`).
     */
    fun currentItem(code: String): ConsentItemDetail =
        jdbc.sql(
            """
            select code, title, version, effective_at, body, purpose, collected_items, retention_period, refusal_disadvantage
              from consent_item
             where code = :code and effective_at <= now()
             order by effective_at desc, version desc
             limit 1
            """,
        )
            .param("code", code)
            .query(ConsentItemDetail::class.java)
            .optional()
            .orElseThrow { TicketException(ErrorCode.CONSENT_ITEM_NOT_FOUND, "그런 동의 항목이 없다: $code") }

    /**
     * 받은 동의가 항목 목록과 맞는지 본다. 안 맞으면 422 다 — 형식은 맞는데 값이 규칙에 안 맞는 자리다(`D5`).
     */
    fun verify(given: Map<String, Boolean?>, items: List<ConsentItem>) {
        val byCode = items.associateBy { it.code }

        given.keys.firstOrNull { it !in byCode }?.let {
            throw TicketException(ErrorCode.UNKNOWN_CONSENT_ITEM, "모르는 동의 항목이다: $it")
        }

        // **값이 null 인 항목을 여기서 잡는다.** JSON 의 `{"marketing_email": null}` 은 타입이 `Boolean` 이어도 들어온다 —
        // 그대로 두면 `not null` 컬럼에 부딪쳐 500 이 되고, 사용자는 무엇을 고쳐야 하는지 못 듣는다.
        given.entries.firstOrNull { it.value == null }?.let {
            throw TicketException(ErrorCode.UNKNOWN_CONSENT_ITEM, "동의 여부가 비어 있다: ${it.key}")
        }

        items.filter { it.required }.firstOrNull { given[it.code] != true }?.let {
            throw TicketException(ErrorCode.REQUIRED_CONSENT_MISSING, "필수 동의 항목이다: ${it.code}")
        }
    }

    /**
     * **담긴 것만** 적는다. 안 담긴 선택 항목은 행이 안 생긴다 — 거부(false 행)와 무응답(행 없음)을 갈라 두려는 것이다.
     */
    fun record(accountId: Long, given: Map<String, Boolean?>, items: List<ConsentItem>, source: String, actorIp: String?) {
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

    /** 없는 칸은 `null` 로 나간다 — 약관엔 정형 넷이 없고 개인정보 항목엔 `body` 가 없다(`D5` 「null 과 생략」) */
    data class ConsentItemDetail(
        val code: String,
        val title: String,
        val version: Int,
        val effectiveAt: OffsetDateTime,
        val body: String?,
        val purpose: String?,
        val collectedItems: String?,
        val retentionPeriod: String?,
        val refusalDisadvantage: String?,
    )
}
