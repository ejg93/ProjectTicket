package com.projectticket.ticket.account

import com.projectticket.ticket.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 파기 배치의 스케줄러 입구(`I17-9`). **빈이 갈린 이유는 트랜잭션 경계다** — [AccountPurgeBatch.purgeDue] 가 `@Transactional` 이라
 * 같은 클래스에서 락을 들고 부르면 자기 호출이 프록시를 안 지난다(`stack.md`).
 *
 * 전에는 `@Scheduled` 가 배치에 바로 붙어 `initialDelay` 없이 파드가 뜰 때마다 모든 파드에서 돌았다. 두 문장이 조건부라 결과는 같지만
 * 같은 행을 대수만큼 잠그고 훑는다. 그래서 락을 지나고 기동 10분 뒤 첫 회다 — 배포가 파드를 줄줄이 띄울 때 겹치지 않는다.
 */
@Component
class AccountPurgeScheduler(
    private val batch: AccountPurgeBatch,
    private val lock: SchedulerLock,
) {

    /** @return 이번 회에 파기한 계정 수. 락을 남이 쥐고 있으면 0 */
    @Scheduled(fixedDelayString = AccountPurgeBatch.PURGE_INTERVAL, initialDelayString = INITIAL_DELAY)
    fun purgeDueExclusively(): Int = lock.runExclusively(LOCK_NAME) { batch.purgeDue() } ?: 0

    companion object {
        const val LOCK_NAME = "account-purge"

        /** 기동 뒤 첫 회까지. 배포가 파드를 줄줄이 띄우는 동안 첫 회가 몰리지 않게 한다 */
        const val INITIAL_DELAY = "PT10M"
    }
}
