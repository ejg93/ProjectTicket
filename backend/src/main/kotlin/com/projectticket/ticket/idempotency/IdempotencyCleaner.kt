package com.projectticket.ticket.idempotency

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 수명이 다한 멱등키를 지운다(`D4` 「멱등키 — 보관 24시간」, `13b`).
 *
 * **정한 지 오래됐는데 지우는 자리가 없었다**(점검 2차). `V7` 의 주석이 「24시간 뒤 지운다」고 적고
 * `idempotency_key_created_idx` 까지 그것을 전제해 만들어졌는데, 스케줄러 열 중 아무도 이 표를 안 봤다 —
 * 표가 무한히 자라고 **`(account_id, key_value)` 유일 제약이 지난 키를 영영 붙들고 있었다.**
 *
 * **24시간인 이유는 재시도의 수명이다.** 멱등키가 막는 것은 「응답을 못 받은 클라이언트의 재시도」인데(`D4`),
 * 하루가 지나 같은 키로 다시 오는 것은 재시도가 아니라 다른 요청이다. 응답 본문을 그만큼 들고 있을 이유도 없다.
 *
 * **지운 뒤 같은 키가 다시 오면 새 요청으로 처리된다.** 그것이 맞다 — 그 시점에는 원래 예매가 이미
 * 확정이든 만료든 끝나 있어서, 좌석 쪽 제약(`reservation_live_hold_idx`)이 두 번째를 받아 낸다.
 *
 * 락을 안 쓴다. [com.projectticket.ticket.outbox.OutboxCleaner] 와 같은 이유다 — 시각 조건이 붙은 `DELETE` 는
 * 두 대가 같이 돌아도 결과가 같고, 늦은 쪽이 0행을 지운다. 락은 정확성이 아니라 절약이라(33) 여기서는 아낄 것이 없다.
 */
@Component
class IdempotencyCleaner(private val jdbc: JdbcClient) {

    private val log = LoggerFactory.getLogger(IdempotencyCleaner::class.java)

    /** @return 지운 키 수 */
    @Scheduled(fixedDelayString = CLEAN_INTERVAL)
    @Transactional
    fun cleanExpired(): Int {
        // 기준은 DB 시계다(`D7`). 앱 시계로 재면 인스턴스마다 다른 경계를 쓴다.
        val deleted = jdbc.sql(
            """
            delete from idempotency_key
             where created_at < now() - make_interval(hours => :hours)
            """,
        )
            .param("hours", RETENTION_HOURS)
            .update()

        if (deleted > 0) {
            log.info("멱등키 청소 삭제={}건 보존={}시간", deleted, RETENTION_HOURS)
        }
        return deleted
    }

    companion object {
        /** `D4` 가 정한 값. 고치려면 그 문서가 먼저다 */
        const val RETENTION_HOURS = 24

        /** 한 시간에 한 번. 아웃박스 청소와 같은 주기다 — 표가 한 시간에 폭발하지 않는다 */
        const val CLEAN_INTERVAL = "PT1H"
    }
}
