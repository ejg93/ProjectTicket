package com.projectticket.ticket.audit

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 감사 로그가 고쳐지지 않는가.
 *
 * **강제 지점이 트리거인 이유는 앱 밖으로도 들어올 수 있어서다.** 코드에 `update` 를 안 쓰는 것만으로는
 * `psql` 로 한 줄 고치는 것을 못 막는데, 감사 로그는 바로 그것을 막으려고 있는 테이블이다.
 * 그래서 이 테스트는 서비스가 아니라 **SQL 을 직접 던진다.**
 */
class AuditImmutabilityTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun recorded_row_cannot_be_updated() {
        val id = insertRow()

        assertThatThrownBy {
            jdbc.sql("update audit_log set event_type = 'tampered' where audit_log_id = :id").param("id", id).update()
        }.hasMessageContaining("감사 로그는 고칠 수 없다")
    }

    @Test
    fun recent_row_cannot_be_deleted() {
        val id = insertRow()

        assertThatThrownBy {
            jdbc.sql("delete from audit_log where audit_log_id = :id").param("id", id).update()
        }.hasMessageContaining("보존 기간")
    }

    @Test
    fun row_past_retention_can_be_deleted() {
        // 보존 3년이 지난 행은 지울 수 있어야 한다. 전부 막으면 파기 배치가 돌 수 없고, 전부 열면 은폐가 된다.
        val id = jdbc.sql(
            """
            insert into audit_log (event_type, actor_account_id, created_at)
            values ('account.logged_in', 1, now() - interval '4 years')
            returning audit_log_id
            """,
        ).query(Long::class.java).single()

        val deleted = jdbc.sql("delete from audit_log where audit_log_id = :id").param("id", id).update()
        assertThat(deleted).isEqualTo(1)
    }

    @Test
    fun table_cannot_be_truncated() {
        insertRow()

        // 행 트리거는 truncate 에 안 걸린다. 한 줄씩 막으면서 통째로 비우는 것을 여는 것은 앞뒤가 안 맞는다.
        assertThatThrownBy { jdbc.sql("truncate audit_log").update() }
            .hasStackTraceContaining("truncate 할 수 없다")
    }

    @Test
    fun consent_history_cannot_be_updated() {
        val accountId = jdbc.sql(
            "insert into account (email, password_hash, display_name) values ('c@test.local', 'x', '이름') returning account_id",
        ).query(Long::class.java).single()
        val itemId = jdbc.sql("select consent_item_id from consent_item where code = 'marketing_email'")
            .query(Long::class.java).single()
        jdbc.sql(
            "insert into account_consent (account_id, consent_item_id, granted, source) values (:acc, :item, true, 'signup')",
        ).param("acc", accountId).param("item", itemId).update()

        // 철회는 행을 더하는 것이다. update 로 갈면 이 설계가 지키려던 이력 그 자체가 사라진다.
        assertThatThrownBy {
            jdbc.sql("update account_consent set granted = false where account_id = :acc").param("acc", accountId).update()
        }.hasStackTraceContaining("동의 이력은 고칠 수 없다")
    }

    private fun insertRow(): Long =
        jdbc.sql(
            """
            insert into audit_log (event_type, actor_account_id, detail)
            values ('account.logged_in', 1, '{}'::jsonb)
            returning audit_log_id
            """,
        ).query(Long::class.java).single()
}
