package com.projectticket.ticket.event

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 못 올린 좌석 판을 고치는 회(`I17-9`). **락이 없다** — 표시가 파드마다 제 메모리라 각자 제 것을 고친다.
 * 표시가 비면 Redis 를 안 부른다.
 */
@Component
class SeatVersionRepairScheduler(private val seatVersions: SeatVersions) {

    /** @return 이번 회에 고친 회차 수 */
    @Scheduled(fixedDelayString = REPAIR_INTERVAL)
    fun repair(): Int = seatVersions.repairDirty()

    companion object {
        /** 화면 폴링(2초)보다 조금 길다 — Redis 가 돌아온 뒤 몇 초 안에 화면이 다시 맞는다 */
        const val REPAIR_INTERVAL = "PT5S"
    }
}
