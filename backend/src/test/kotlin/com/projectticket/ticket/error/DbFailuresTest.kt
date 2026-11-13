package com.projectticket.ticket.error

import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.QueryTimeoutException
import org.springframework.jdbc.CannotGetJdbcConnectionException

/**
 * DB 시간 상한에 걸린 실패를 503 `service-busy` 로 가르나(`I17-6`). 판정은 원인 사슬의 SQLSTATE 다 — Spring 의 번역 예외 종류는
 * 드라이버·판에 따라 갈려서(`stack.md`) 그것으로 가르면 같은 상한이 어떤 날은 500 이 된다.
 */
class DbFailuresTest {

    @Test
    fun a_lock_timeout_is_busy() {
        assertThat(CannotAcquireLockException("잠금 대기 초과", SQLException("canceling statement due to lock timeout", "55P03")).isDbBusy()).isTrue()
    }

    @Test
    fun a_statement_timeout_is_busy() {
        assertThat(QueryTimeoutException("문장 시간 초과", SQLException("canceling statement due to statement timeout", "57014")).isDbBusy()).isTrue()
    }

    @Test
    fun a_pool_wait_timeout_is_busy() {
        assertThat(CannotGetJdbcConnectionException("풀이 비었다", SQLTransientConnectionException("Connection is not available, request timed out after 3000ms")).isDbBusy())
            .isTrue()
    }

    @Test
    fun a_deadlock_is_not_busy() {
        // 교착은 재시도 대상이다(`D4` 「재시도」) — 여기까지 오면 재시도도 진 것이라 500 이다.
        assertThat(CannotAcquireLockException("교착", SQLException("deadlock detected", "40P01")).isDbBusy()).isFalse()
    }

    @Test
    fun a_bug_is_not_busy() {
        assertThat(IllegalStateException("우리 잘못").isDbBusy()).isFalse()
    }
}
