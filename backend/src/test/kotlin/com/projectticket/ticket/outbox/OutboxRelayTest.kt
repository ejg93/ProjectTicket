package com.projectticket.ticket.outbox

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * 릴레이가 안 나간 것만 가져가 발행하고 표시하는가(`D11`).
 *
 * **표 쪽만 본다**(28 뒤로). 브로커로 무엇이 갔나는 `KafkaRelayTest` 가 보고, 여기는 「안 나간 것만 가져가 표시하나」다.
 *
 * **롤백 레인이다.** 릴레이가 표만 읽고 남의 미커밋 데이터를 안 봐서 여기서 잴 수 있다(소비자와 다른 자리 — `stack.md`).
 * 스케줄러는 꺼져 있다(`SchedulingConfig`) — 회차를 손으로 부른다.
 */
class OutboxRelayTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var relay: OutboxRelay

    @Test
    fun relay_publishes_unpublished_events_and_marks_them() {
        val eventId = insertEvent()

        assertThat(relay.relay()).isEqualTo(1)

        // **여기서 보는 것은 표다.** 브로커로 무엇이 갔나는 `KafkaRelayTest` 가 본다(28) —
        // 소비가 비동기라 같은 테스트에서 둘을 같이 재면 무엇이 느린 것인지 못 가른다.
        assertThat(publishedAtIsSet(eventId)).isTrue()
    }

    @Test
    fun a_published_event_is_not_sent_again() {
        insertEvent()
        relay.relay()

        // 표시된 행은 부분 인덱스에서 빠진다. 두 번째 회가 0 이 아니면 같은 메일이 계속 나간다.
        assertThat(relay.relay()).isZero()
    }

    @Test
    fun an_event_cannot_be_edited_except_for_publication() {
        val eventId = insertEvent()

        // 페이로드를 나중에 고치면 「그때 무슨 일이 났나」가 거짓이 되고, 이미 나간 사건과 표가 어긋난다.
        assertThatThrownBy {
            jdbc.sql("update outbox set payload = '{}'::jsonb where event_id = :id").param("id", eventId).update()
        }.hasStackTraceContaining("사건은 고칠 수 없다")
    }

    @Test
    fun the_same_event_id_cannot_be_recorded_twice() {
        val eventId = insertEvent()

        // 소비자 멱등의 키다. 겹치면 둘째 사건이 첫째로 오인돼 조용히 안 처리된다.
        assertThatThrownBy { insertEvent(eventId) }.hasStackTraceContaining("outbox_event_id_key")
    }

    private fun insertEvent(eventId: UUID = UUID.randomUUID()): UUID {
        jdbc.sql(
            """
            insert into outbox (event_id, type, aggregate_type, aggregate_id, payload)
            values (:id, 'reservation.reserved', 'reservation', 7, '{"reservation_id": 7}'::jsonb)
            """,
        ).param("id", eventId).update()
        return eventId
    }

    private fun publishedAtIsSet(eventId: UUID): Boolean =
        jdbc.sql("select published_at is not null from outbox where event_id = :id")
            .param("id", eventId).query(Boolean::class.java).single()

}
