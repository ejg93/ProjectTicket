package com.projectticket.ticket.payment

import com.projectticket.ticket.reservation.ReservationStatus
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * 결과가 우리 표에 안 닿은 결제 시도를 PG 에 다시 묻는다(`I17-1`, `D4` 「결제」).
 *
 * 결과 반영(③)이 롤백되면 PG 는 승인했는데 결제 행이 없다. 만료 스윕이 예매를 `expired` 로 옮기고, 환불 큐는 결제 행을 찾으므로
 * 아무도 그 돈을 안 돌려준다. 여기서 같은 PG 키로 [MockPaymentGateway.inquire] 해 승인이면 [PaymentTransitionService.settle] 을 다시 부른다 —
 * 예매가 이미 `reserved` 가 아니라 확정이 0행이고, 그것이 기존 `late` 경로(결제 행 + 감사 `payment.late` → 환불 큐)다. 새 경로를 안 만든다.
 *
 * **대상은 「미결이고 예매가 `paying` 이 아닌 시도」다.** `paying` 이면 원 요청이 아직 PG 를 기다리는 중일 수 있다 —
 * 기한이 지나면 `HoldSweeper` 가 `expired` 로 옮기고 그다음 회가 잡는다. PG 가 모르는 키와 거절은 `settled_at` 만 찍는다.
 *
 * **트랜잭션이 없다.** PG 를 부르는 자리라(`D4` 「트랜잭션 경계」) 대상만 읽고, 행마다 [PaymentTransitionService] 의 트랜잭션 하나다.
 * 행마다 격리한다 — 한 건이 계속 실패해도 뒤 시도가 그 앞에서 안 끊긴다. 대사와 늦게 돌아온 원 요청이 같이 `settle` 하면
 * `payment_approved_idx` 가 하나를 막고, 막힌 쪽은 여기서 WARN 으로 넘긴다 — 다음 회는 닫힌 `settled_at` 을 본다.
 *
 * 모의 PG 의 기억은 JVM 마다라 다른 파드의 시도는 `inquire` 가 null 이다 — 실물 PG 에서는 풀린다.
 */
@Component
class PaymentReconciler(
    private val jdbc: JdbcClient,
    private val gateway: MockPaymentGateway,
    private val transitions: PaymentTransitionService,
) {

    private val log = LoggerFactory.getLogger(PaymentReconciler::class.java)

    data class Attempt(val reservationId: Long, val accountId: Long, val gatewayKey: String, val amount: Int)

    /** @return 이번 회가 되살린 승인 수 */
    fun reconcile(): Int {
        val due = takeUnsettled()
        var recovered = 0
        due.forEach { attempt ->
            try {
                if (reconcileOne(attempt)) recovered++
            } catch (e: RuntimeException) {
                log.warn("결제 대사 실패 reservation_id={} — 다음 회에 다시 묻는다", attempt.reservationId, e)
            }
        }
        if (due.isNotEmpty()) {
            log.info("결제 대사 대상 {}건, 되살린 승인 {}건", due.size, recovered)
        }
        return recovered
    }

    private fun reconcileOne(attempt: Attempt): Boolean {
        val verdict = gateway.inquire(attempt.gatewayKey)
        if (verdict == null || !verdict.approved) {
            transitions.closeAttempt(attempt.gatewayKey)
            return false
        }
        val settled = transitions.settle(
            accountId = attempt.accountId,
            paying = PaymentTransitionService.Paying(attempt.reservationId, attempt.amount, attempt.gatewayKey),
            verdict = verdict,
        )
        // 돈은 받았는데 기록이 없던 것 — 사람이 볼 것이라 WARN 이다.
        log.warn("대사가 승인을 되살렸다 reservation_id={} payment_id={} late={}", attempt.reservationId, settled.paymentId, settled.late)
        return true
    }

    private fun takeUnsettled(): List<Attempt> =
        jdbc.sql(
            """
            select a.reservation_id, a.account_id, a.gateway_key, a.amount
              from payment_attempt a
              join reservation r on r.reservation_id = a.reservation_id
             where a.settled_at is null and r.status <> :paying
             order by a.payment_attempt_id
             limit :batch
            """,
        )
            .param("paying", ReservationStatus.PAYING.code)
            .param("batch", BATCH_SIZE)
            .query(Attempt::class.java)
            .list()
            .filterNotNull()

    companion object {
        /** 한 회 주기. 만료 스윕(30초) 뒤에 잡히므로 승인이 기록되기까지 최악 결제 기한 3분 + 30초 + 1분이다 */
        const val RECONCILE_INTERVAL = "PT1M"

        /** 한 회 상한. PG 를 행마다 부르므로 한 회가 락 TTL 안에 끝나게 묶는다 */
        const val BATCH_SIZE = 100
    }
}
