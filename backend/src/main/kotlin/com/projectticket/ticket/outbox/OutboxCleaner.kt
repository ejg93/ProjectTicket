package com.projectticket.ticket.outbox

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 발행이 끝난 봉투를 지운다(`D2` 수명표 — 「발행 후 보존 기간 뒤 삭제」).
 *
 * 안 지우면 이 표가 **영원히 자란다.** 릴레이가 미발행 부분 인덱스로 고르니 조회는 안 느려지지만,
 * 백업·복제·`vacuum` 이 지난 1년치 사건을 계속 나른다.
 *
 * **보존은 7일이다.** 재발행 요구가 없어서(소비자는 표를 다시 읽는다, `D11`) 사건을 오래 들 이유가 없고,
 * 한 주면 「어제 그 알림이 왜 안 갔나」를 사람이 물어보는 기간을 덮는다. 감사(3년)와 다른 표라 규정과 무관하다.
 *
 * **안 나간 봉투는 안 건드린다.** `published_at is null` 은 릴레이가 아직 할 일이 있는 것이고,
 * 그것을 지우면 사건이 조용히 사라진다 — 계속 실패하는 봉투를 치우는 자리는 DLQ(29)다.
 */
@Component
class OutboxCleaner(private val jdbc: JdbcClient) {

    private val log = LoggerFactory.getLogger(OutboxCleaner::class.java)

    /** @return 지운 봉투 수 */
    @Scheduled(fixedDelayString = CLEAN_INTERVAL)
    @Transactional
    fun cleanPublished(): Int {
        // 기준은 DB 시계다(`D7`). 앱 시계로 재면 인스턴스마다 다른 날을 지운다.
        val deleted = jdbc.sql(
            """
            delete from outbox
             where published_at is not null
               and published_at < now() - make_interval(days => :days)
            """,
        )
            .param("days", RETENTION_DAYS)
            .update()

        if (deleted > 0) {
            log.info("아웃박스 청소 삭제={}건 보존={}일", deleted, RETENTION_DAYS)
        }
        return deleted
    }

    companion object {
        /** 발행 뒤 이만큼 지나면 지운다. 재발행 요구가 생기면 이 값이 아니라 `D11` 이 먼저 바뀐다 */
        const val RETENTION_DAYS = 7

        /** 하루에 한 번이면 충분하다 — 표가 한 시간에 폭발하지 않는다. 주기를 줄여도 지우는 총량은 같다 */
        const val CLEAN_INTERVAL = "PT1H"
    }
}
