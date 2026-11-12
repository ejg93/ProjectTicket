package com.projectticket.ticket.settlement

import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 집계를 마친 정산서(`pending`). 정산서는 `scheduled` 로만 태어나서(`V33`) 넣고 전이표대로 옮긴다.
 *
 * `settle_at` 을 `now()` 로 넣어 `V30` 의 「정산일 전 집계」 거절을 안 탄다 — 같은 트랜잭션의 `now()` 라 같다.
 * 롤백 바탕에서만 쓴다 — 금액·항목 합의 지연 트리거는 커밋 때 돌아서 여기서는 안 본다.
 */
fun pendingSettlement(jdbc: JdbcClient, performanceId: Long, policyId: Long, amount: Int): Long {
    val settlementId = jdbc.sql(
        "insert into settlement (performance_id, settlement_policy_id, settle_at) values (:performance, :policy, now()) returning settlement_id",
    ).param("performance", performanceId).param("policy", policyId).query(Long::class.java).single()
    jdbc.sql("update settlement set status = 'pending', amount = :amount, settled_at = now() where settlement_id = :id")
        .param("amount", amount)
        .param("id", settlementId)
        .update()
    return settlementId
}
