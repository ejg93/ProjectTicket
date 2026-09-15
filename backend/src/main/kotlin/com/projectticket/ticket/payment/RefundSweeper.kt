package com.projectticket.ticket.payment

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 아직 안 나간 환불(`requested`)을 PG 로 보낸다(`17b` ⓐ — 17a 가 흡수했다).
 *
 * 두 자리가 이것을 만든다:
 * - 관객 취소(17)가 ② 뒤 ③ 전에 죽어 `requested` 로 남은 것
 * - **회차 취소(17a)가 만든 전액 환불** — 예매가 수백 건이면 PG 호출도 수백 번이라 그 트랜잭션에서 안 부른다(`D4`)
 *
 * **같은 키로 다시 보내는 것이 안전하다**(`D4`). 키는 우리 환불 id 고 PG 가 같은 키에 같은 답을 준다 —
 * 이미 나간 환불을 또 보내도 돈이 두 번 안 나간다. 그래서 「보냈는지 모르겠다」를 재시도로 푼다.
 *
 * **트랜잭션은 [RefundTransitionService.complete] 에만 있다.** 여기 붙여도 자기 호출이라 프록시를 안 지난다(`stack.md`) —
 * 그래서 고르기는 트랜잭션 밖 한 문장이고, 「둘이 같은 행을 집었나」를 막는 것은 `complete` 의 조건부 UPDATE 다(`D4`).
 */
@Component
class RefundSweeper(
    private val jdbc: JdbcClient,
    private val gateway: MockPaymentGateway,
    private val transitions: RefundTransitionService,
) {

    private val log = LoggerFactory.getLogger(RefundSweeper::class.java)

    /** @return 내보낸 환불 수 */
    @Scheduled(fixedDelayString = SWEEP_INTERVAL)
    fun sweep(): Int {
        val pending = takeRequested()
        if (pending.isEmpty()) {
            log.debug("환불 발송 — 보낼 것 없음")
            return 0
        }

        // **행마다 격리한다.** 한 건이 계속 실패해도 `order by refund_id` 뒤의 환불이 그 앞에서 안 끊긴다 —
        // 돈이 걸린 줄이라 머리 막힘이 곧 「영영 안 나가는 환불」이다(`PerformanceCloser` 와 같은 판단).
        var done = 0
        pending.forEach { row ->
            try {
                val result = gateway.refund(row.refundId.toString(), row.refundAmount)
                if (transitions.complete(row.refundId, result.refundNumber)) done++
            } catch (e: RuntimeException) {
                log.warn("환불 발송 실패 refund_id={} — 다음 회에 다시 보낸다", row.refundId, e)
            }
        }

        log.info("환불 발송 {}건", done)
        return done
    }

    /**
     * **잠그지 않는다.** 자기 호출이라 트랜잭션이 안 열리고, 트랜잭션 없는 `for update skip locked` 는 문장이 끝나는 순간 풀려
     * 「둘이 같은 행을 안 집는다」를 못 준다 — 걸어 두면 안 도는 강제 지점이 하나 늘 뿐이다.
     *
     * 겹쳐 집는 것은 그대로 두고 **[RefundTransitionService.complete] 의 조건부 UPDATE 가 승자를 하나로 만든다**(`D4`).
     * 그 사이 PG 로 두 번 나갈 수는 있지만 키가 우리 환불 id 라 같은 답이 오고 돈이 두 번 안 나간다.
     */
    fun takeRequested(): List<Row> =
        jdbc.sql(
            """
            select refund_id, refund_amount
              from refund
             where status = 'requested'
             order by refund_id
             limit :batch
            """,
        ).param("batch", BATCH_SIZE).query(Row::class.java).list().filterNotNull()

    data class Row(val refundId: Long, val refundAmount: Int)

    companion object {
        /** 돈이 걸린 자리라 짧다. 관객이 취소 화면에서 「환불 처리됨」을 기다리는 것은 아니지만(응답은 이미 갔다) 늦을 이유도 없다 */
        const val SWEEP_INTERVAL = "PT10S"

        const val BATCH_SIZE = 100
    }
}
