package com.projectticket.ticket.queue

import com.projectticket.ticket.SchedulerLock
import com.projectticket.ticket.event.PerformanceStatus
import com.projectticket.ticket.observability.TicketMetrics
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 5초마다 판매 중인 회차의 줄을 훑어 들인다(`D12`). 무엇을 들일지는 [AdmissionService] 의 Lua 가 정한다.
 *
 * **고르는 것과 들이는 것이 갈려 있다** — 회차 목록은 DB 가 주고 입장은 Redis 안에서 끝난다.
 * 회차마다 스크립트가 따로 돌아서 한 회차가 정원이 차도 다른 회차가 안 막힌다.
 *
 * 인스턴스가 여럿이면 대수만큼 돈다. 같은 사람을 두 번 들이지는 않는다(Lua 가 원자다). **속도는 락이 아니라 스크립트가 지킨다** —
 * 락은 겹침만 막고 끝나면 놓아서, 셋이면 5초에 많게는 세 번 돈다. 입장 스크립트가 예산의 창(`admit_window`)을 보고
 * 이 주기에 남은 예산만큼만 들인다(`35f`, 마무리 21차). 3차 측정이 그 전의 모양(R 의 약 2.2배)을 쟀다(`load-3` 「35c」).
 */
@Component
class AdmissionScheduler(
    private val jdbc: JdbcClient,
    private val admission: AdmissionService,
    private val redis: StringRedisTemplate,
    private val metrics: TicketMetrics,
    private val lock: SchedulerLock,
) {

    private val log = LoggerFactory.getLogger(AdmissionScheduler::class.java)

    /** @return 이번에 들인 사람 수 */
    /** 스케줄러 입구(33). 락은 셋이 같은 순간에 회차 목록을 같이 훑지 않게 한다 — 속도는 [AdmissionService.admit] 이 지킨다(`35f`) */
    @Scheduled(fixedDelayString = AdmissionService.ADMIT_INTERVAL)
    fun admitDueExclusively(): Long = lock.runExclusively(LOCK_NAME) { admitDue() } ?: 0

    fun admitDue(): Long {
        val open = jdbc.sql("select performance_id from performance where status = :open order by performance_id")
            .param("open", PerformanceStatus.OPEN.code)
            .query(Long::class.java)
            .list()
            .filterNotNull()

        val admitted = open.sumOf { admission.admit(it) }
        // 게이지는 **회차 합**이다(`D10`). 회차를 태그로 달면 회차마다 시계열이 늘어 저장소가 터진다.
        metrics.queueSizes(
            length = open.sumOf { redis.opsForZSet().size(QueueKeys.waiting(it)) ?: 0 },
            active = open.sumOf { redis.opsForZSet().size(QueueKeys.active(it)) ?: 0 },
        )
        if (admitted > 0) {
            log.info("대기열 입장 회차={}건 입장={}명", open.size, admitted)
        }
        return admitted
    }

    companion object {
        /** 락 이름(33). 겹침만 막는다 — 속도는 입장 예산의 창이 지킨다(`35f`) */
        const val LOCK_NAME = "admission-scheduler"
    }
}
