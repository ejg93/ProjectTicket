package com.projectticket.ticket.idempotency

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.event.EventFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 멱등키 청소가 지키는 선(`D4` 「멱등키 — 보관 24시간」, 청크 `13b` 의 닫힘 조건).
 *
 * **강제 지점이 테스트인 이유**는 지우는 조건이 `where` 한 줄이라 제약으로 못 거는 것이다.
 * 틀리는 방향이 둘이고 **둘 다 아무 오류도 안 낸다**: 너무 일찍 지우면 재시도가 저장된 응답 대신
 * 새 요청으로 처리되고, 안 지우면 표가 자라면서 `(account_id, key_value)` 유일 제약이 지난 키를 붙든다.
 */
class IdempotencyCleanerTest : PostgresTestBase() {

    @Autowired lateinit var cleaner: IdempotencyCleaner
    @Autowired lateinit var jdbc: JdbcClient

    private var accountId: Long = 0

    @BeforeEach
    fun setUp() {
        accountId = EventFixture(jdbc).account("${PREFIX}${System.nanoTime()}@test.local")
    }

    @Test
    fun deletes_only_what_is_past_the_retention() {
        val old = key(hoursAgo = IdempotencyCleaner.RETENTION_HOURS + 1)
        val fresh = key(hoursAgo = 1)

        cleaner.cleanExpired()

        assertThat(exists(old)).isFalse()
        // 보존 안이다. 응답을 못 받은 클라이언트가 아직 같은 키로 올 수 있다(`D4`).
        assertThat(exists(fresh)).isTrue()
    }

    @Test
    fun the_boundary_is_still_kept() {
        val onTheLine = key(hoursAgo = IdempotencyCleaner.RETENTION_HOURS)

        // 경계는 남긴다. 한 시간 차이로 지우는 쪽이 틀리면 아무도 눈치 못 챈다.
        // 롤백 레인이라 `now()` 가 트랜잭션 시각으로 고정돼서 이 판정이 흔들리지 않는다(`D7`).
        cleaner.cleanExpired()
        assertThat(exists(onTheLine)).isTrue()
    }

    @Test
    fun cleaning_twice_deletes_nothing_more() {
        key(hoursAgo = IdempotencyCleaner.RETENTION_HOURS + 1)

        assertThat(cleaner.cleanExpired()).isPositive()
        // 두 번째 회가 0 이 아니면 조건이 시각이 아니라 다른 것에 걸린 것이다.
        assertThat(cleaner.cleanExpired()).isZero()
    }


    @Test
    fun the_retention_is_the_number_the_document_decided() {
        // 위 셋은 전부 상수로 데이터를 넣어서 **상수를 48 로 바꿔도 초록이다**(마무리 7차 독립 리뷰).
        // 값을 정한 것은 `D4` 라 그 숫자를 여기 박는다 — 고치려면 문서가 먼저고, 그때 이 줄이 선다.
        assertThat(IdempotencyCleaner.RETENTION_HOURS)
            .describedAs("`D4` 「멱등키 — 보관 24시간」이 정한 값이다")
            .isEqualTo(24)
    }

    private fun key(hoursAgo: Int): String {
        val value = "key-$hoursAgo-${System.nanoTime()}"
        jdbc.sql(
            """
            insert into idempotency_key (account_id, key_value, request_hash, response_body, created_at)
            values (:account, :key, repeat('a', 64), '{}'::jsonb, now() - make_interval(hours => :hours))
            """,
        )
            .param("account", accountId)
            .param("key", value)
            .param("hours", hoursAgo)
            .update()
        return value
    }

    private fun exists(keyValue: String): Boolean =
        jdbc.sql("select exists(select 1 from idempotency_key where account_id = :account and key_value = :key)")
            .param("account", accountId).param("key", keyValue).query(Boolean::class.java).single()

    private companion object {
        const val PREFIX = "idem-clean-"
    }
}
