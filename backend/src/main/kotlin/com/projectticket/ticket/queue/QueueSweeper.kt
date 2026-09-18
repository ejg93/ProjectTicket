package com.projectticket.ticket.queue

import com.projectticket.ticket.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 하트비트가 끊긴 사람을 줄에서 뺀다(`D12` 「이탈과 정리」, 24).
 *
 * 브라우저를 닫은 사람이 줄에 남아 있으면 **뒷사람이 그만큼 늦게 들어간다** — 입장은 앞에서부터라
 * 유령 하나가 자리 하나를 계속 먹는다. 폴링이 2초라 90초면 열 번 넘게 빠뜨린 것이고, 그건 떠난 것이다.
 *
 * 주기가 선점 스윕(30초)과 같다. 더 자주 돌 이유가 없다 — 90초 기준이라 30초 오차는 그 안이다.
 */
@Component
class QueueSweeper(
    private val jdbc: JdbcClient,
    private val queue: QueueService,
    private val lock: SchedulerLock,
) {

    private val log = LoggerFactory.getLogger(QueueSweeper::class.java)

    /** @return 이번에 뺀 사람 수 */
    /** 스케줄러 입구(33). 락은 바깥 고리에만 있다 */
    @Scheduled(fixedDelayString = SWEEP_INTERVAL)
    fun sweepDueExclusively(): Long = lock.runExclusively(LOCK_NAME) { sweepDue() } ?: 0

    fun sweepDue(): Long {
        val open = jdbc.sql("select performance_id from performance where status = 'open' order by performance_id")
            .query(Long::class.java)
            .list()
            .filterNotNull()

        val removed = open.sumOf { queue.sweepStale(it) }
        if (removed > 0) {
            log.info("대기열 이탈 정리 회차={}건 제거={}명", open.size, removed)
        }
        return removed
    }

    companion object {
        /** 락 이름(33) */
        const val LOCK_NAME = "queue-sweeper"

        /** `D7` 「자동 전이의 주기와 기준」의 스윕과 같은 30초 */
        const val SWEEP_INTERVAL = "PT30S"
    }
}
