package com.projectticket.ticket.settlement

import com.projectticket.ticket.outbox.EventType
import com.projectticket.ticket.outbox.OutboxRelay
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 정산 표를 만지는 트랜잭션 전부. [schedule] 은 사건을 받아 예약하고, [settle] 은 `settle_at` 이 지난 것을 집계한다(사용자 선택 — 둘이 갈려 있다).
 *
 * [schedule] 이 `REQUIRES_NEW` 인 이유는 알림(26)과 같다 — 28 전에는 릴레이가 자기 트랜잭션에서 동기로 발행해서
 * 경계가 없으면 정산 쪽 실패가 `published_at` 을 막아 같은 사건이 영영 재발행됐다. **28 이 그 사슬을 끊었다**(브로커 너머 다른 트랜잭션이다).
 * `D11` 의 「소비자 하나의 결함이 발행자를 멈추지 않는다」는 이제 프로세스 경계가 지킨다.
 *
 * 스케줄러와 빈이 갈린 이유는 자기 호출이다(`stack.md`).
 */
@Component
class SettlementStore(private val jdbc: JdbcClient) {

    /**
     * 회차 종료·취소 사건을 정산 예약으로. **금액 0, 항목 없음** — 집계는 `settle_at` 뒤다.
     *
     * **취소된 회차도 정산서를 만든다**(`D21` 「회차 취소」) — 항목이 전부 0 이고, 「이 회차는 취소돼서 0 이다」가 기록으로 남는다.
     * 매출은 `reserved` 만 세고 취소 수수료는 `audience` 만 세므로(`settle`) 계산이 저절로 0 이다 — 분기가 없다.
     *
     * `settle_at` 은 봉투의 `occurred_at` 기준이다 — **사건이 일어난 시각**이라 종료든 취소든 같은 뜻이고, 릴레이가 늦어도 안 흔들린다.
     *
     * 두 번 받아도 `settlement_performance_id_key` 가 둘째를 0행으로 끝낸다(`D11` 멱등). 예약 표를 따로 안 둔 이유가 이것이다 —
     * 정산서의 유일 제약이 그 일을 이미 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun schedule(envelope: OutboxRelay.Envelope) {
        if (envelope.type != EventType.PERFORMANCE_CLOSED && envelope.type != EventType.PERFORMANCE_CANCELLED) return

        jdbc.sql(
            """
            insert into settlement (performance_id, settlement_policy_id, settle_at)
            select p.performance_id, p.settlement_policy_id, :occurredAt::timestamptz + make_interval(days => sp.payout_delay_days)
              from performance p join settlement_policy sp on sp.settlement_policy_id = p.settlement_policy_id
             where p.performance_id = :performance
            on conflict (performance_id) do nothing
            """,
        )
            .param("performance", envelope.aggregateId)
            .param("occurredAt", envelope.occurredAt)
            .update()
            .let { inserted ->
                // 0행은 **이미 예약됨**이거나 **회차에 정책이 없음**이다. 뒤쪽은 결함이라 그 자리에서 드러낸다 —
                // 안 그러면 「닫힌 회차 + 발행된 사건 + 정산서 없음」이 조용히 남고 사건은 다시 안 온다.
                if (inserted == 0 && !isScheduled(envelope.aggregateId)) {
                    throw IllegalStateException("정산을 예약 못 했다 — 회차에 정책이 없다: performance_id=${envelope.aggregateId}")
                }
            }
    }

    private fun isScheduled(performanceId: Long): Boolean =
        jdbc.sql("select count(*) from settlement where performance_id = :id").param("id", performanceId).query(Long::class.java).single() > 0

    /**
     * 집계할 것을 고른다. `for update skip locked` 로 **한 회에 같은 행을 둘이 안 집는다** — 다만 이 트랜잭션이 끝나면 락이 풀리므로,
     * 두 번 집계되는 것을 실제로 막는 것은 [settle] 의 조건부 UPDATE(`and status = 'scheduled'`)다.
     */
    @Transactional
    fun takeDue(batchSize: Int): List<Due> =
        jdbc.sql(
            """
            select s.settlement_id, s.performance_id, sp.commission_rate, sp.cancel_fee_share
              from settlement s join settlement_policy sp on sp.settlement_policy_id = s.settlement_policy_id
             where s.status = 'scheduled' and s.settle_at <= now()
             order by s.settlement_id
             limit :batch
               for update of s skip locked
            """,
        ).param("batch", batchSize).query(Due::class.java).list().filterNotNull()

    /**
     * 항목을 넣고 정산서를 `pending` 으로. **한 트랜잭션이다** — 합계와 항목이 갈리면 지연 트리거가 커밋 때 막는다.
     *
     * 반올림은 **항목마다 한 번, 원 단위 half-up**(`D21`). 좌석마다 반올림하면 합이 안 맞는다.
     */
    @Transactional
    fun settle(due: Due): Int? {
        val sale = saleOf(due.performanceId)
        val platformFee = -round(sale, due.commissionRate)
        val cancelFee = round(cancelFeeBaseOf(due.performanceId), due.cancelFeeShare)
        val lines = listOf(
            SettlementLineKind.SALE to sale,
            SettlementLineKind.PLATFORM_FEE to platformFee,
            SettlementLineKind.CANCEL_FEE to cancelFee,
        )
        val amount = lines.sumOf { it.second }

        val moved = jdbc.sql(
            """
            update settlement set status = :pending, amount = :amount, settled_at = now()
             where settlement_id = :id and status = 'scheduled'
            """,
        )
            .param("pending", SettlementStatus.PENDING.code)
            .param("amount", amount)
            .param("id", due.settlementId)
            .update()
        // 조건부다 — 남이 먼저 집계했으면 0행이고 항목도 그쪽이 넣었다. null 은 「내가 안 했다」고, 0 은 「0원으로 집계했다」다.
        if (moved == 0) return null

        lines.forEach { (kind, lineAmount) ->
            jdbc.sql("insert into settlement_line (settlement_id, kind, amount) values (:id, :kind, :amount)")
                .param("id", due.settlementId).param("kind", kind.code).param("amount", lineAmount).update()
        }
        return amount
    }

    /** 판매된 것 — `reserved` 예매의 좌석 가격 합(`D21`). 취소·만료된 예매는 안 든다 */
    private fun saleOf(performanceId: Long): Int =
        jdbc.sql(
            """
            select coalesce(sum(rs.price), 0)
              from reservation_seat rs join reservation r on r.reservation_id = rs.reservation_id
             where r.performance_id = :id and r.status = 'reserved'
            """,
        ).param("id", performanceId).query(Int::class.java).single()

    /** 관객 취소의 수수료 합. 회차 취소·승인 지연은 수수료가 0 이라(`D6`) 여기 안 든다 */
    private fun cancelFeeBaseOf(performanceId: Long): Int =
        jdbc.sql(
            """
            select coalesce(sum(rf.fee_amount), 0)
              from refund rf
              join payment pm on pm.payment_id = rf.payment_id
              join reservation r on r.reservation_id = pm.reservation_id
             where r.performance_id = :id and rf.reason = 'audience'
            """,
        ).param("id", performanceId).query(Int::class.java).single()

    private fun round(base: Int, rate: BigDecimal): Int =
        BigDecimal(base).multiply(rate).setScale(0, RoundingMode.HALF_UP).intValueExact()

    data class Due(val settlementId: Long, val performanceId: Long, val commissionRate: BigDecimal, val cancelFeeShare: BigDecimal)
}
