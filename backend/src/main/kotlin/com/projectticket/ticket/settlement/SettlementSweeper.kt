package com.projectticket.ticket.settlement

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * `settle_at` 이 지난 예약을 집계한다(27, 사용자 선택 — 사건 소비와 집계가 갈려 있다).
 *
 * **주기가 한 시간이다.** D+7 짜리 예약이라 분 단위로 훑을 이유가 없다 — 「정산이 한 시간 늦다」는 아무도 안 겪는다.
 * 스윕 넷 중 가장 길다(릴레이 1초 · 알림 5초 · 선점 만료 30초 · 정산 1시간) — 주기는 **기다리는 사람이 누구인지**가 정한다.
 */
@Component
class SettlementSweeper(private val store: SettlementStore) {

    private val log = LoggerFactory.getLogger(SettlementSweeper::class.java)

    /** @return 만든 정산서 수 */
    @Scheduled(fixedDelayString = SWEEP_INTERVAL)
    fun sweep(): Int {
        val due = store.takeDue(BATCH_SIZE)
        if (due.isEmpty()) {
            log.debug("정산 집계 — 대상 없음")
            return 0
        }

        var settled = 0
        var total = 0
        due.forEach {
            // 0원 정산서도 만든다 — 전부 취소된 회차가 「0 이다」로 기록에 남는다(`D21` 「회차 취소」).
            total += store.settle(it)
            settled++
        }

        log.info("정산 집계 정산서={}건 합계={}원", settled, total)
        return settled
    }

    companion object {
        const val SWEEP_INTERVAL = "PT1H"
        const val BATCH_SIZE = 100
    }
}
