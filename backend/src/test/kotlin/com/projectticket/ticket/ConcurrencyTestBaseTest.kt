package com.projectticket.ticket

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 바탕 자체를 잰다 — 스레드마다 자기 트랜잭션으로 **커밋**되는가, 정리가 접두 행만 지우는가.
 *
 * 이것이 통과해야 위에 올리는 동시성 테스트(13·14·16)의 실패가 바탕이 아니라 코드를 가리킨다.
 */
class ConcurrencyTestBaseTest : ConcurrencyTestBase() {

    @Test
    fun hundred_threads_each_commit_their_own_row() {
        val results = runConcurrently(100) { index -> insertAccount("$PREFIX$index@test.local") }

        // 하나라도 롤백되거나 예외로 죽었으면 바탕이 트랜잭션을 잘못 쥔 것이다. 100 을 세기 전에 먼저 본다.
        assertThat(results).allSatisfy { assertThat(it.isSuccess).describedAs(it.exceptionOrNull()?.toString()).isTrue() }
        assertThat(countPrefixed()).isEqualTo(100)
    }

    @Test
    fun purge_removes_only_prefixed_rows() {
        val bystander = insertAccount("bystander@test.local")
        insertAccount("${PREFIX}victim@test.local")

        purgePrefixedRows()

        assertThat(countPrefixed()).isZero()
        assertThat(jdbc.sql("select count(*) from account where account_id = :id").param("id", bystander).query(Long::class.java).single())
            .describedAs("남의 행을 지우면 그쪽 테스트가 조용히 깨진다")
            .isOne()
        jdbc.sql("delete from account where account_id = :id").param("id", bystander).update()
    }

    private fun insertAccount(email: String): Long =
        jdbc.sql(
            """
            insert into account (email, password_hash, display_name, role)
            values (:email, 'x', '이름', 'audience')
            returning account_id
            """,
        ).param("email", email).query(Long::class.java).single()

    private fun countPrefixed(): Long =
        jdbc.sql("select count(*) from account where email like :prefix").param("prefix", "$PREFIX%").query(Long::class.java).single()
}
