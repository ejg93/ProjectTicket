package com.projectticket.ticket.error

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 제약 위반 예외가 행의 값을 싣지 않는다(`I13-4`, `D10` 「개인정보는 안 찍는다」).
 *
 * PG 는 위반한 행을 DETAIL(`Failing row contains (…)`)에 통째로 적고, pgjdbc 는 `logServerErrorDetail` 이 켜져 있으면(기본) 그것을 예외 메시지에 붙인다.
 * 그 예외가 마지막 그물(`ApiExceptionHandler`)에 닿으면 이메일·해시가 로그에 실린다. 제약 이름은 본문(`violates check constraint "…"`)에 남아야 한다 —
 * `OrganizerEventService` 가 그것으로 판매 창 위반을 가른다.
 */
class PgErrorDetailTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun a_constraint_violation_does_not_carry_the_row() {
        val thrown = catchThrowable {
            // 값은 바인딩한다 — 앱이 그렇게 쓴다(`D14` 「SQL」). 글자로 박으면 SQL 본문이 예외 메시지에 실려 값이 보인다.
            jdbc.sql("insert into account (email, password_hash, display_name, role) values (:email, :hash, repeat('a', 51), 'audience')")
                .param("email", "leak@test.local")
                .param("hash", "hash-leak")
                .update()
        }

        val messages = generateSequence(thrown) { it.cause }.joinToString(" / ") { it.message.orEmpty() }
        assertThat(messages).contains("account_display_name_length_check")
        assertThat(messages).doesNotContain("Failing row").doesNotContain("leak@test.local").doesNotContain("hash-leak")
    }
}
