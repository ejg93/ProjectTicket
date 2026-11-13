package com.projectticket.ticket.outbox

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

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
 * 그것을 지우면 사건이 조용히 사라진다 — 계속 실패하는 봉투는 릴레이가 10회 뒤 주차하고 사람이 푼다(`I17-4`, `event-catalog.md` 「발행」).
 *
 * **한 문장에 [BATCH_SIZE] 행씩, 덜 지울 때까지 되풀이한다**(`I17-14`). 쌓인 것을 한 문장으로 지우면 문장 상한 10초에 걸려 매번 실패하고 더 쌓인다.
 * 트랜잭션을 안 건다 — 문장 하나가 곧 트랜잭션이라 배치마다 커밋되고 긴 트랜잭션이 안 남는다.
 */
@Component
class OutboxCleaner(private val jdbc: JdbcClient) {

    private val log = LoggerFactory.getLogger(OutboxCleaner::class.java)

    /** @return 지운 봉투 수 */
    @Scheduled(fixedDelayString = CLEAN_INTERVAL)
    fun cleanPublished(): Int = clean(BATCH_SIZE)

    /** 한 문장에 [batch] 행씩, 지운 수가 [batch] 보다 적을 때까지. @return 지운 봉투 수 */
    fun clean(batch: Int): Int {
        var deleted = 0
        do {
            // 기준은 DB 시계다(`D7`). 앱 시계로 재면 인스턴스마다 다른 날을 지운다.
            val round = jdbc.sql(
                """
                delete from outbox
                 where outbox_id in (
                     select outbox_id from outbox
                      where published_at is not null
                        and published_at < now() - make_interval(days => :days)
                      order by published_at
                      limit :batch)
                   and published_at is not null
                   and published_at < now() - make_interval(days => :days)
                """,
            )
                .param("days", RETENTION_DAYS)
                .param("batch", batch)
                .update()
            // 바깥에도 같은 조건을 건다 — 잠금을 기다린 뒤 다시 보는 것은 바깥 `where` 뿐이라, 그사이 `published_at` 을 비운 봉투를 안 지운다.
            // 배치보다 많이 지웠으면 `limit` 이 빠진 것이다 — 시험이 이 줄로 상한을 문다(마무리 37차 독립 리뷰 I2).
            check(round <= batch) { "한 문장이 배치보다 많이 지웠다 — limit 이 빠졌다: $round > $batch" }
            deleted += round
        } while (round == batch)

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

        /** 한 문장에 지우는 행 수(`I17-14`). 문장 상한 10초 안에 넉넉히 끝난다 */
        const val BATCH_SIZE = 1_000
    }
}
