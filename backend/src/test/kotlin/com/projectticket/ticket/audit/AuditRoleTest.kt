package com.projectticket.ticket.audit

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 감사 로그의 권한 분리(4a의 닫힘 조건, `D9`).
 *
 * **이것이 강제 지점 1번에 가장 가까운 자리다.** `V3` 의 보존 트리거는 표 주인이 `disable trigger` 로 끌 수 있는데,
 * 권한은 문장이 파싱되기 전에 막는다 — 끄려면 권한을 다시 주는 문장을 남겨야 하고 그것은 흔적이 남는다.
 *
 * 테스트 바탕은 주인으로 붙는다(감사 정리에 `session_replication_role` 이 필요해서다). 그래서 여기서는
 * **트랜잭션 안에서 `set local role`** 로 그 역할을 입어 본다 — 운영에서 앱이 붙자마자 입는 것과 같은 역할이다.
 */
class AuditRoleTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun app_role_cannot_delete() {
        seedAudit()

        assertThatThrownBy { asRole("ticket_app") { jdbc.sql("delete from audit_log").update() } }
            .describedAs("앱 역할이 감사를 지울 수 있으면 사고가 난 뒤 그 기록부터 사라진다")
            .hasMessageContaining("audit_log")
    }

    @Test
    fun app_role_cannot_rewrite_history() {
        val id = seedAudit()

        // 고치는 것은 지우는 것보다 나쁘다 — 없어진 것은 눈에 띄는데 바뀐 것은 안 띈다.
        assertThatThrownBy {
            asRole("ticket_app") {
                jdbc.sql("update audit_log set event_type = 'account.logged_in' where audit_log_id = :id")
                    .param("id", id).update()
            }
        }.hasMessageContaining("audit_log")
    }

    @Test
    fun app_role_still_writes_the_log() {
        // 쌓는 것까지 막으면 감사 자체가 안 된다. 회수한 것은 고치기·지우기뿐이다.
        val id = asRole("ticket_app") { seedAudit() }

        assertThat(id).isPositive()
    }

    @Test
    fun only_the_purge_role_can_delete() {
        seedAudit(yearsAgo = 4)

        val deleted = asRole("ticket_purge") {
            jdbc.sql("delete from audit_log where created_at < now() - interval '3 years'").update()
        }

        assertThat(deleted).isEqualTo(1)
    }

    @Test
    fun the_purge_role_cannot_write() {
        // 지우는 역할이 쓰기까지 하면 「쌓고 지우는」 한 사람이 된다. 가른 뜻이 없어진다.
        assertThatThrownBy { asRole("ticket_purge") { seedAudit() } }.hasMessageContaining("audit_log")
    }

    /** 트랜잭션 안에서만 그 역할을 입는다. `set local` 이라 트랜잭션이 끝나면 되돌아간다 */
    private fun <T> asRole(role: String, work: () -> T): T =
        TransactionTemplate(transactionManager).execute {
            jdbc.sql("set local role $role").update()
            work()
        }!!

    /** @return 만든 행의 id */
    private fun seedAudit(yearsAgo: Int = 0): Long =
        jdbc.sql(
            """
            insert into audit_log (event_type, actor_account_id, created_at)
            values ('account.signed_up', null, now() - make_interval(years => :years))
            returning audit_log_id
            """,
        ).param("years", yearsAgo).query(Long::class.java).single()
}
