package com.projectticket.ticket.reservation

import com.projectticket.ticket.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 선점 만료 스윕의 스케줄러 입구(33). **빈이 갈린 이유는 트랜잭션 경계다.**
 *
 * 같은 클래스 안에서 `@Transactional` 함수를 부르면 프록시를 안 지나 트랜잭션이 통째로 무시된다(`stack.md`).
 * 락을 [HoldSweeper] 안에 두면 스케줄러 경로만 조용히 트랜잭션 없이 돌고 — 예매 만료와 좌석 해제가 갈린 자동 커밋이 되어
 * **둘째가 실패하면 그 좌석이 영영 안 풀린다.** `RefundSweeper`·`PerformanceCloser` 가 같은 이유로 갈려 있다.
 *
 * 락은 여기 바깥 고리에만 있다 — [HoldSweeper.sweep] 은 테스트가 직접 부르는 자리라 락을 안 든다.
 */
@Component
class HoldSweepScheduler(
    private val sweeper: HoldSweeper,
    private val lock: SchedulerLock,
) {

    /** @return 이번 회가 만료시킨 예매 수. 락을 남이 쥐고 있으면 0 */
    @Scheduled(fixedDelayString = HoldSweeper.SWEEP_INTERVAL)
    fun sweepDue(): Int = lock.runExclusively(LOCK_NAME) { sweeper.sweep() } ?: 0

    companion object {
        /** 락 이름. 인스턴스가 셋이어도 이 이름 하나를 두고 다툰다(33) */
        const val LOCK_NAME = "hold-sweeper"
    }
}
