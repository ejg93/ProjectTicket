package com.projectticket.ticket.outbox

import java.util.concurrent.CompletionException
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 릴레이가 실패를 일시·영구로 가르나(`I17-4`). 브로커 없이 판정 함수만 잰다.
 *
 * 일시 실패(브로커 장애·시간 초과)를 시도 수로 세면 장애 13분에 모든 사건이 주차된다 — 그래서 원인 사슬의
 * Kafka `RetriableException` 은 세지 않고 그 회를 멈춘다. 그 밖은 사건 탓이라 센다.
 */
class OutboxRelayFailureTest {

    @Test
    fun a_broker_timeout_is_transient_even_when_wrapped() {
        assertThat(OutboxRelay.isTransient(CompletionException(KafkaException("보내기 실패", TimeoutException("시간 초과"))))).isTrue()
    }

    @Test
    fun a_record_too_large_is_not_transient() {
        assertThat(OutboxRelay.isTransient(CompletionException(KafkaException("보내기 실패", RecordTooLargeException("2MB"))))).isFalse()
    }

    @Test
    fun a_bug_in_building_the_envelope_is_not_transient() {
        assertThat(OutboxRelay.isTransient(IllegalStateException("모르는 사건 이름"))).isFalse()
    }
}
