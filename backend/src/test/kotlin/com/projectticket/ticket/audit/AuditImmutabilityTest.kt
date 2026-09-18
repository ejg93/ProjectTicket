package com.projectticket.ticket.audit

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * 감사 로그가 고쳐지지 않는가.
 *
 * **강제 지점이 트리거인 이유는 앱 밖으로도 들어올 수 있어서다.** 코드에 `update` 를 안 쓰는 것만으로는
 * `psql` 로 한 줄 고치는 것을 못 막는데, 감사 로그는 바로 그것을 막으려고 있는 테이블이다.
 * 그래서 이 테스트는 서비스가 아니라 **SQL 을 직접 던진다.**
 */
class AuditImmutabilityTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    /**
     * **권한이 먼저 막는다**(4a). 앱이 입는 역할에는 `update` 자체가 없어서 트리거까지 안 간다 —
     * 그래서 트리거를 재려면 권한이 있는 자리에서 던져야 한다(`set local role none` — 그 트랜잭션 동안만 주인이다).
     */
    @Test
    fun recorded_row_cannot_be_updated() {
        val id = insertRow()

        assertThatThrownBy {
            attempt { jdbc.sql("update audit_log set event_type = 'tampered' where audit_log_id = :id").param("id", id).update() }
        }.describedAs("앱 역할은 권한에서 막힌다").hasStackTraceContaining("permission denied")

        assertThatThrownBy {
            attempt(asOwner = true) {
                jdbc.sql("update audit_log set event_type = 'tampered' where audit_log_id = :id").param("id", id).update()
            }
        }.describedAs("권한이 있어도 트리거가 막는다").hasStackTraceContaining("감사 로그는 고칠 수 없다")
    }

    @Test
    fun recent_row_cannot_be_deleted() {
        val id = insertRow()

        assertThatThrownBy {
            attempt(asOwner = true) { jdbc.sql("delete from audit_log where audit_log_id = :id").param("id", id).update() }
        }.hasStackTraceContaining("보존 기간")
    }

    /**
     * 실패를 보는 문장은 **저장점 안에서** 던진다. 한 번 실패한 트랜잭션은 그 뒤 문장을 전부 거절해서
     * (`current transaction is aborted`) 같은 테스트의 다음 단언이 무엇을 재는지 알 수 없게 된다.
     *
     * @param asOwner 권한 뒤에 있는 트리거를 재려면 권한을 통과해야 한다. `set local` 이라 저장점이 풀리면 역할도 되돌아간다
     */
    private fun attempt(asOwner: Boolean = false, work: () -> Unit) {
        TransactionTemplate(transactionManager)
            .apply { propagationBehavior = TransactionDefinition.PROPAGATION_NESTED }
            .executeWithoutResult {
                if (asOwner) jdbc.sql("set local role none").update()
                work()
            }
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

        // 지우는 것은 파기 역할이다(4a). 앱 역할에는 그 권한이 없다.
        jdbc.sql("set local role ticket_purge").update()
        val deleted = jdbc.sql("delete from audit_log where audit_log_id = :id").param("id", id).update()
        jdbc.sql("set local role ticket_app").update()
        assertThat(deleted).isEqualTo(1)
    }

    @Test
    fun table_cannot_be_truncated() {
        insertRow()

        // 행 트리거는 truncate 에 안 걸린다. 한 줄씩 막으면서 통째로 비우는 것을 여는 것은 앞뒤가 안 맞는다.
        assertThatThrownBy { attempt(asOwner = true) { jdbc.sql("truncate audit_log").update() } }
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
