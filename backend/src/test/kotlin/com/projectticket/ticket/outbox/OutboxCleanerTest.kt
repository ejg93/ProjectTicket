package com.projectticket.ticket.outbox

import com.projectticket.ticket.PostgresTestBase
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 아웃박스 청소가 지키는 선(`D2` 수명표, 청크 25a의 닫힘 조건).
 *
 * **강제 지점이 테스트인 이유**는 지우는 조건이 `where` 한 줄이라 제약으로 못 거는 것이다 —
 * 조건이 틀리면 **안 나간 사건이 조용히 사라진다**. 그 실패는 아무 오류도 안 낸다.
 */
class OutboxCleanerTest : PostgresTestBase() {

    @Autowired lateinit var cleaner: OutboxCleaner
    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun deletes_only_what_was_published_long_enough_ago() {
        val old = envelope(publishedDaysAgo = OutboxCleaner.RETENTION_DAYS + 1)
        val fresh = envelope(publishedDaysAgo = 1)
        val unpublished = envelope(publishedDaysAgo = null)

        assertThat(cleaner.cleanPublished()).isEqualTo(1)

        assertThat(exists(old)).isFalse()
        // 보존 기간 안이다. 어제 그 알림이 왜 안 갔나를 아직 물어볼 수 있다.
        assertThat(exists(fresh)).isTrue()
        // **안 나간 봉투를 지우면 사건이 조용히 사라진다.** 계속 실패하는 것을 치우는 자리는 DLQ(29)다.
        assertThat(exists(unpublished)).isTrue()
    }

    @Test
    fun the_boundary_day_is_still_kept() {
        val onTheLine = envelope(publishedDaysAgo = OutboxCleaner.RETENTION_DAYS)

        // 경계는 남긴다. 하루 차이로 지우는 쪽이 틀리면 아무도 눈치 못 챈다.
        assertThat(cleaner.cleanPublished()).isZero()
        assertThat(exists(onTheLine)).isTrue()
    }

    @Test
    fun cleaning_twice_deletes_nothing_more() {
        envelope(publishedDaysAgo = OutboxCleaner.RETENTION_DAYS + 1)

        assertThat(cleaner.cleanPublished()).isEqualTo(1)
        assertThat(cleaner.cleanPublished()).isZero()
    }

    /** @param publishedDaysAgo null 이면 아직 안 나간 봉투 */
    private fun envelope(publishedDaysAgo: Int?): UUID {
        val eventId = UUID.randomUUID()
        jdbc.sql(
            """
            insert into outbox (event_id, type, aggregate_type, aggregate_id, payload, published_at)
            values (
                :id, 'reservation.reserved', 'reservation', 7, '{"reservation_id": 7}'::jsonb,
                case when :days::int is null then null else now() - make_interval(days => :days::int) end
            )
            """,
        ).param("id", eventId).param("days", publishedDaysAgo).update()
        return eventId
    }

    private fun exists(eventId: UUID): Boolean =
        jdbc.sql("select exists(select 1 from outbox where event_id = :id)")
            .param("id", eventId).query(Boolean::class.java).single()
}
