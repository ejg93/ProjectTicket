package com.projectticket.ticket.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.QueryTimeoutException
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.BackOffExecution
import tools.jackson.databind.ObjectMapper

/**
 * 소비 실패를 일시·사건 탓으로 갈라 재시도하나(`I17-7`). 브로커 없이 고르는 함수만 잰다.
 *
 * DB 가 재시도 창(2초 × 3)보다 오래 죽으면 그동안 소비한 사건이 전부 DLT 로 갔다 — 사람이 아웃박스로 다시 넣어야 끝난다.
 * 일시 오류는 무제한 지수(2초부터 두 배, 최대 30초)로 기다리고, 깨진 봉투는 다시 해도 같아 바로 DLT 다.
 */
class ConsumerBackOffTest {

    @Test
    fun a_dead_database_is_retried_without_limit() {
        listOf(
            CannotGetJdbcConnectionException("풀이 연결을 못 얻었다"),
            QueryTimeoutException("문장 시간 초과"),
            CannotCreateTransactionException("트랜잭션을 못 열었다"),
        ).forEach { cause ->
            val waits = intervals(ConsumerErrorHandling.backOffFor(ListenerExecutionFailedException("리스너가 던졌다", cause)), limit = 50)

            assertThat(waits).describedAs("${cause.javaClass.simpleName} 은 DLT 로 안 간다").hasSize(50)
            assertThat(waits.take(3)).containsExactly(2_000L, 4_000L, 8_000L)
            assertThat(waits.max()).describedAs("max.poll.interval.ms 300초 안").isEqualTo(30_000L)
        }
    }

    @Test
    fun a_bug_still_gets_three_tries_then_the_dead_letter_topic() {
        val waits = intervals(ConsumerErrorHandling.backOffFor(ListenerExecutionFailedException("리스너가 던졌다", IllegalStateException("예매가 없다"))), limit = 50)

        assertThat(waits).containsExactly(2_000L, 2_000L, 2_000L)
    }

    @Test
    fun a_broken_envelope_goes_straight_to_the_dead_letter_topic() {
        val broken = checkNotNull(runCatching { ObjectMapper().readTree("{깨진") }.exceptionOrNull()) { "깨진 JSON 이 읽혔다" }

        val waits = intervals(ConsumerErrorHandling.backOffFor(ListenerExecutionFailedException("리스너가 던졌다", broken)), limit = 50)

        assertThat(waits).describedAs("다시 읽어도 깨져 있다").isEmpty()
    }

    /** 멈출 때까지(또는 [limit] 까지) 대기 간격을 모은다 */
    private fun intervals(backOff: BackOff, limit: Int): List<Long> {
        val execution = backOff.start()
        return generateSequence { execution.nextBackOff() }.takeWhile { it != BackOffExecution.STOP }.take(limit).toList()
    }
}
