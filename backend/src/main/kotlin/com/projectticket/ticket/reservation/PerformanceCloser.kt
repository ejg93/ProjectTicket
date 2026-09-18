package com.projectticket.ticket.reservation

import com.projectticket.ticket.SchedulerLock
import com.projectticket.ticket.queue.QueueService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 판매 마감이 지난 회차를 닫는다(`D3` 「자동 전이 셋」 — 1분마다).
 *
 * **고르는 것과 닫는 것이 갈려 있다.** 고르기는 트랜잭션 밖이라 목록이 길어도 잠금을 안 쥐고,
 * 닫기는 회차마다 [PerformanceCloseService] 의 트랜잭션이다 — 하나가 죽어도 나머지는 닫힌다(사용자 선택).
 *
 * 주기가 스윕(30초)보다 긴 이유는 **마감이 1분 늦어도 아무도 손해를 안 보기 때문**이다 — 그 사이 들어온 선점은
 * 어차피 `held` 라 닫을 때 만료되고, 좌석은 돌아간다.
 */
@Component
class PerformanceCloser(
    private val jdbc: JdbcClient,
    private val closeService: PerformanceCloseService,
    private val queue: QueueService,
    private val lock: SchedulerLock,
) {

    private val log = LoggerFactory.getLogger(PerformanceCloser::class.java)

    /** @return 닫은 회차 수 */
    /** 스케줄러 입구(33). 락은 바깥 고리에만 있다 — [closeDue] 는 테스트가 직접 부른다 */
    @Scheduled(fixedDelayString = CLOSE_INTERVAL)
    fun closeDueExclusively(): Int = lock.runExclusively(LOCK_NAME) { closeDue() } ?: 0

    fun closeDue(): Int {
        val due = jdbc.sql("select performance_id from performance where status = 'open' and sales_close_at < now() order by performance_id")
            .query(Long::class.java)
            .list()
            .filterNotNull()

        if (due.isEmpty()) {
            log.debug("회차 자동 종료 — 대상 없음")
            return 0
        }

        var closed = 0
        var expired = 0
        due.forEach { performanceId ->
            try {
                closeService.close(performanceId)?.let {
                    closed++
                    expired += it
                    // 줄을 걷는 것은 **커밋 뒤**다(`D12`). close() 가 트랜잭션 경계라 여기는 이미 그 밖이고,
                    // 안에서 지우면 롤백된 종료가 남의 줄을 날린다 — Redis 에는 되돌릴 방법이 없다.
                    queue.drop(performanceId)
                }
            } catch (e: RuntimeException) {
                // 고르고 나서 닫기까지 기획사가 취소했거나 남이 먼저 닫았을 수 있다. 회차 하나를 실패로 만들어 나머지를 안 막는다 —
                // DB 예외만 잡으면 사건 기록·감사에서 난 것이 루프를 끊는다.
                log.warn("회차 종료 건너뜀 performance_id={} 이유={}", performanceId, e.javaClass.simpleName)
            }
        }

        log.info("회차 자동 종료 회차={}건 만료={}건", closed, expired)
        return closed
    }

    companion object {
        /** 락 이름(33) */
        const val LOCK_NAME = "performance-closer"

        /** `D7` 「자동 전이의 주기와 기준」 — 1분 */
        const val CLOSE_INTERVAL = "PT1M"
    }
}
