package com.projectticket.ticket.payment

import com.projectticket.ticket.observability.elapsedMillis
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * 관객 취소(`D6`). ① 취소 + 환불 행 → ② PG 환불(트랜잭션 밖) → ③ 환불 `done`.
 *
 * 멱등키를 안 받는다(`D4` 「멱등키」) — 이미 있는 자원의 상태를 옮기는 것이라 ① 의 잠금과 조건부 UPDATE 가 둘째 요청을 `invalid-transition` 으로 끝낸다.
 * PG 환불의 멱등키는 우리 환불 id 다 — 요청 자체가 우리 자원이라 그 번호가 곧 키다.
 *
 * ② 뒤 ③ 전에 죽으면 환불 행이 `requested` 로 남는다. 모의 PG 는 즉시 답하므로 지금은 그 구간이 없다시피 하고, 되살리는 스윕은 `17b` 다.
 */
@Service
class RefundService(private val gateway: MockPaymentGateway, private val transitions: RefundTransitionService) {

    private val log = LoggerFactory.getLogger(RefundService::class.java)

    /** 취소 결과. 취소 화면(44)이 이 값을 그대로 보여준다 — 「얼마를 돌려받나」가 여기 있다 */
    data class Result(
        val refundId: Long,
        val reservationId: Long,
        val daysBefore: Int,
        val tierRate: BigDecimal,
        val feeAmount: Int,
        val refundAmount: Int,
        val status: String,
    )

    /** @param expectedRefund 화면이 본 환불액. 지금 계산과 다르면 ① 이 409 `quote-changed` 로 되돌린다(`44a-1a`) */
    fun cancel(accountId: Long, reservationId: Long, expectedRefund: Int): Result {
        val requested = transitions.request(accountId, reservationId, expectedRefund)

        val started = System.nanoTime()
        val refunded = gateway.refund(requested.refundId.toString(), requested.refundAmount)
        log.info("mock-pg refund refund_id={} ms={} amount={}", requested.refundId, elapsedMillis(started), requested.refundAmount)

        val done = transitions.complete(requested.refundId, refunded.refundNumber)
        return Result(
            refundId = requested.refundId,
            reservationId = requested.reservationId,
            daysBefore = requested.daysBefore,
            tierRate = requested.tierRate,
            feeAmount = requested.feeAmount,
            refundAmount = requested.refundAmount,
            status = if (done) "done" else "requested",
        )
    }
}
