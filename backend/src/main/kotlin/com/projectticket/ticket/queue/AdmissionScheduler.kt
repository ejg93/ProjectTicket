package com.projectticket.ticket.queue

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 5초마다 판매 중인 회차의 줄을 훑어 들인다(`D12`). 무엇을 들일지는 [AdmissionService] 의 Lua 가 정한다.
 *
 * **고르는 것과 들이는 것이 갈려 있다** — 회차 목록은 DB 가 주고 입장은 Redis 안에서 끝난다.
 * 회차마다 스크립트가 따로 돌아서 한 회차가 정원이 차도 다른 회차가 안 막힌다.
 *
 * 인스턴스가 여럿이면 대수만큼 돈다. 같은 사람을 두 번 들이지는 않지만(Lua 가 원자다) 속도가 대수배가 되므로
 * 33 이 Redis 락으로 하나만 돌린다 — 속도가 설정값과 같아야 31 의 측정이 뜻을 갖는다.
 */
@Component
class AdmissionScheduler(private val jdbc: JdbcClient, private val admission: AdmissionService) {

    private val log = LoggerFactory.getLogger(AdmissionScheduler::class.java)

    /** @return 이번에 들인 사람 수 */
    @Scheduled(fixedDelayString = AdmissionService.ADMIT_INTERVAL)
    fun admitDue(): Long {
        val open = jdbc.sql("select performance_id from performance where status = 'open' order by performance_id")
            .query(Long::class.java)
            .list()
            .filterNotNull()

        val admitted = open.sumOf { admission.admit(it) }
        if (admitted > 0) {
            log.info("대기열 입장 회차={}건 입장={}명", open.size, admitted)
        }
        return admitted
    }
}
