package com.projectticket.ticket.outbox

import com.projectticket.ticket.PostgresTestBase
import org.apache.kafka.common.errors.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture

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
    @Autowired lateinit var json: ObjectMapper

    /**
     * **커밋 레인이 남긴 사건을 이 테스트의 회차에서 뺀다.**
     *
     * 소비자 테스트(`ConsumerIdempotencyTest`)가 진짜 예매를 커밋하면 안 나간 outbox 행이 남고,
     * 테스트 스케줄러가 꺼져 있어서 아무도 안 치운다 — 릴레이가 그것까지 집어 회차 수가 1 이 아니게 된다.
     * 로컬은 컨테이너를 재사용해 지난 회차가 이미 치워 둔 탓에 안 보였고, CI 의 새 컨테이너에서 드러났다.
     *
     * **릴레이를 부르지 않고 표시만 한다.** 부르면 남의 사건이 진짜로 브로커에 나가 소비자가 행을 만든다.
     * 롤백 레인이라 이 표시도 테스트가 끝나면 되돌아간다.
     */
    @BeforeEach
    fun setOlderEventsAside() {
        jdbc.sql("update outbox set published_at = now() where published_at is null").update()
    }

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

    @Test
    fun a_poison_row_does_not_block_other_aggregates() {
        val poison = insertPoison(aggregateId = 7_001)
        val other = insertEvent(aggregateId = 7_002)

        // 독 행이 배치 맨 앞에 있어도 뒤의 다른 집합체 사건은 이 회에 나간다(`I17-4`).
        assertThat(relay.relay()).isEqualTo(1)
        assertThat(publishedAtIsSet(other)).isTrue()

        val failure = failureOf(poison)
        assertThat(failure.published).isFalse()
        assertThat(failure.attempts).isEqualTo(1)
        assertThat(failure.lastError).describedAs("예외 클래스 이름만 남긴다(D10)").contains("RecordTooLargeException")
        assertThat(failure.waiting).describedAs("지수 대기 — 다음 시도는 미래다").isTrue()
    }

    @Test
    fun a_later_event_of_the_poisoned_aggregate_waits_behind_it() {
        insertPoison(aggregateId = 7_003)
        val behind = insertEvent(aggregateId = 7_003)

        relay.relay()

        // 같은 집합체의 사건은 순서를 지킨다(`event-catalog.md`) — 앞이 막히면 뒤도 선다.
        assertThat(publishedAtIsSet(behind)).isFalse()
    }

    @Test
    fun parked_and_waiting_rows_are_not_taken() {
        val parked = insertEvent(aggregateId = 7_004)
        val waiting = insertEvent(aggregateId = 7_005)
        jdbc.sql("update outbox set attempts = 10 where event_id = :id").param("id", parked).update()
        jdbc.sql("update outbox set attempts = 1, next_attempt_at = now() + interval '1 hour' where event_id = :id").param("id", waiting).update()

        assertThat(relay.relay()).isZero()
    }

    @Test
    fun a_broker_timeout_halts_the_round_without_counting_an_attempt() {
        insertEvent(aggregateId = 7_011)
        val halted = insertEvent(aggregateId = 7_012)
        val untouched = insertEvent(aggregateId = 7_013)
        val kafka = brokerThat(
            sent(),
            CompletableFuture.failedFuture(TimeoutException("브로커가 답이 없다")),
        )

        // 일시 오류는 브로커 탓이라 행을 안 센다 — 회를 멈추고 다음 회에 그대로 다시 간다(`I17-4`).
        assertThat(OutboxRelay(jdbc, json, kafka).relay(OutboxRelay.ROUND_BUDGET)).isEqualTo(1)

        val failure = failureOf(halted)
        assertThat(failure.published).isFalse()
        assertThat(failure.attempts).isZero()
        assertThat(failure.lastError).isNull()
        assertThat(publishedAtIsSet(untouched)).isFalse()
        verify(kafka, times(2)).send(anyString(), anyString(), anyString())
    }

    @Test
    fun a_spent_budget_stops_before_the_next_row() {
        insertEvent(aggregateId = 7_021)
        val next = insertEvent(aggregateId = 7_022)
        @Suppress("UNCHECKED_CAST")
        val kafka = mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
        `when`(kafka.send(anyString(), anyString(), anyString())).thenAnswer {
            // 느린 브로커 — 예산(50ms)을 이 한 건이 다 쓴다. 예산은 행 사이에서만 본다.
            Thread.sleep(SLOW_SEND_MS)
            sent()
        }

        assertThat(OutboxRelay(jdbc, json, kafka).relay(Duration.ofMillis(50))).isEqualTo(1)

        assertThat(publishedAtIsSet(next)).describedAs("남은 행은 다음 회다").isFalse()
        verify(kafka, times(1)).send(anyString(), anyString(), anyString())
    }

    @Test
    fun the_tenth_failure_parks_the_row() {
        val poison = insertPoison(aggregateId = 7_031)
        jdbc.sql("update outbox set attempts = 9, next_attempt_at = null where event_id = :id").param("id", poison).update()

        relay.relay()
        assertThat(failureOf(poison).attempts).isEqualTo(OutboxRelay.PARK_AFTER)

        // 주차된 행은 다시 안 잡힌다 — 푸는 것은 사람이다(`event-catalog.md` 「발행」).
        jdbc.sql("update outbox set next_attempt_at = null where event_id = :id").param("id", poison).update()
        relay.relay()
        assertThat(failureOf(poison).attempts).isEqualTo(OutboxRelay.PARK_AFTER)
    }

    /** 순서대로 결과를 돌려주는 가짜 브로커. 더 부르면 Mockito 가 마지막 답을 되풀이한다 — 몇 번 불렀나는 `verify(times(…))` 가 본다 */
    @Suppress("UNCHECKED_CAST")
    private fun brokerThat(vararg results: CompletableFuture<SendResult<String, String>>): KafkaTemplate<String, String> {
        val kafka = mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
        `when`(kafka.send(anyString(), anyString(), anyString())).thenReturn(results.first(), *results.drop(1).toTypedArray())
        return kafka
    }

    private fun sent(): CompletableFuture<SendResult<String, String>> = CompletableFuture.completedFuture(null)

    private fun insertPoison(aggregateId: Long): UUID {
        val eventId = UUID.randomUUID()
        // 2MB — 프로듀서 `max.request.size`(1MB)를 넘어 `RecordTooLargeException` 이다. 몇 번을 다시 보내도 같다.
        jdbc.sql(
            """
            insert into outbox (event_id, type, aggregate_type, aggregate_id, payload)
            values (:id, 'reservation.reserved', 'reservation', :aggregate,
                    jsonb_build_object('reservation_id', :aggregate, 'pad', repeat('x', 2000000)))
            """,
        ).param("id", eventId).param("aggregate", aggregateId).update()
        return eventId
    }

    private data class Failure(val published: Boolean, val attempts: Int, val lastError: String?, val waiting: Boolean)

    private fun failureOf(eventId: UUID): Failure =
        jdbc.sql(
            """
            select published_at is not null as published, attempts, last_error, next_attempt_at > now() as waiting
              from outbox where event_id = :id
            """,
        )
            .param("id", eventId)
            .query { rs, _ ->
                Failure(
                    published = rs.getBoolean("published"),
                    attempts = rs.getInt("attempts"),
                    lastError = rs.getString("last_error"),
                    waiting = rs.getBoolean("waiting"),
                )
            }
            .single()

    private fun insertEvent(eventId: UUID = UUID.randomUUID(), aggregateId: Long = 7): UUID {
        jdbc.sql(
            """
            insert into outbox (event_id, type, aggregate_type, aggregate_id, payload)
            values (:id, 'reservation.reserved', 'reservation', :aggregate, jsonb_build_object('reservation_id', :aggregate))
            """,
        ).param("id", eventId).param("aggregate", aggregateId).update()
        return eventId
    }

    private fun publishedAtIsSet(eventId: UUID): Boolean =
        jdbc.sql("select published_at is not null from outbox where event_id = :id")
            .param("id", eventId)
            .query(Boolean::class.java)
            .single()

    private companion object {
        const val SLOW_SEND_MS = 100L
    }
}
