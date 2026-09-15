package com.projectticket.ticket.settlement

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 지금 효력 있는 정산 정책을 고른다(`D21` 「고르는 규칙」) — **그 기획사의 행 중 `effective_at <= now()` 인 최신, 없으면 기본 행 중 최신.**
 *
 * `version` 이나 최신 id 로 고르면 시행 전 판을 집는다. 고르는 기준은 언제나 `effective_at` 이다(`stack.md` — 동의 항목·환불 구간표와 같은 규칙).
 * 시행 전 판이 없는 동안에는 어느 방법이든 같은 답을 줘서, **개정판을 처음 넣는 날까지 아무도 모른다.**
 */
@Component
class SettlementPolicyQuery(private val jdbc: JdbcClient) {

    fun currentFor(organizerId: Long): Policy =
        jdbc.sql(
            """
            select settlement_policy_id, commission_rate, cancel_fee_share, payout_delay_days
              from settlement_policy
             where effective_at <= now()
               and (organizer_id = :organizer or organizer_id is null)
             order by (organizer_id is null), effective_at desc
             limit 1
            """,
        )
            .param("organizer", organizerId)
            .query(Policy::class.java)
            .optional()
            .orElseThrow { IllegalStateException("효력 있는 정산 정책이 없다: organizer_id=$organizerId") }

    data class Policy(
        val settlementPolicyId: Long,
        val commissionRate: BigDecimal,
        val cancelFeeShare: BigDecimal,
        val payoutDelayDays: Int,
    )
}
