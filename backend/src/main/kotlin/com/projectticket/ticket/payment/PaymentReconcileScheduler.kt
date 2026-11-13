package com.projectticket.ticket.payment

import com.projectticket.ticket.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 결제 대사의 스케줄러 입구(`I17-1`). `HoldSweepScheduler` 와 같은 꼴이다 — 락은 바깥 고리에만 두고,
 * [PaymentReconciler.reconcile] 은 시험이 직접 부르는 자리라 락을 안 든다.
 *
 * 대수만큼 겹치면 같은 시도를 PG 에 여러 번 묻고 `settle` 이 겹친다 — 막는 것은 `payment_approved_idx` 지만 WARN 이 대수만큼 난다.
 */
@Component
class PaymentReconcileScheduler(
    private val reconciler: PaymentReconciler,
    private val lock: SchedulerLock,
) {

    /** @return 이번 회가 되살린 승인 수. 락을 남이 쥐고 있으면 0 */
    @Scheduled(fixedDelayString = PaymentReconciler.RECONCILE_INTERVAL)
    fun reconcileDue(): Int = lock.runExclusively(LOCK_NAME) { reconciler.reconcile() } ?: 0

    companion object {
        const val LOCK_NAME = "payment-reconciler"
    }
}
