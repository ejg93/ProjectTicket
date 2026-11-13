package com.projectticket.ticket.reservation

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 만료 스윕이 예매 표를 처음부터 훑지 않나(`I17-15`, `D4`).
 *
 * `order by reservation_id limit` 은 부분 인덱스를 버린다 — 만료가 id 끝에 몰리면 플래너가 PK 를 순회하며 Filter 로 걸러,
 * 한 회가 표 크기에 비례해 읽었다(2026-11-13 설계 실측, 500 건에 302,500 행). 계획을 글자가 아니라 **읽은 행 수**로 잰다 —
 * `pg_stat_xact_all_tables` 는 트랜잭션 안에서 바로 보인다.
 *
 * 그 트랜잭션에서만 `reservation` 을 임시 표로 가린다(`on commit drop`, 롤백 레인). 임시 표에는 트리거·외래키가 안 따라오고,
 * 상태와 시각 열을 대조하는 `check` 둘은 떼어 끝난 예매를 짧게 넣는다.
 */
class HoldSweepReadsTest : PostgresTestBase() {

    @Autowired lateinit var sweeper: HoldSweeper
    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun one_round_reads_the_expired_rows_not_the_whole_table() {
        jdbc.sql("create temp table reservation (like public.reservation including all) on commit drop").update()
        jdbc.sql(
            """
            alter table pg_temp.reservation
                drop constraint reservation_status_fields_check,
                drop constraint reservation_cancelled_by_status_check
            """,
        ).update()
        // 끝난 예매가 앞을 채우고, 그 뒤에 만료된 선점이 몰린다 — id 순서로 고르면 앞을 다 걸러야 닿는다.
        jdbc.sql(
            """
            insert into pg_temp.reservation (account_id, performance_id, status, total_amount, held_until)
            select g, 1, 'reserved', 0, now() from generate_series(1, :finished) g
            """,
        ).param("finished", FINISHED).update()
        jdbc.sql(
            """
            insert into pg_temp.reservation (account_id, performance_id, status, total_amount, held_until)
            select :finished + g, 1, 'held', 0, now() - interval '1 minute' from generate_series(1, :expired) g
            """,
        ).param("finished", FINISHED).param("expired", EXPIRED).update()
        jdbc.sql("analyze pg_temp.reservation").update()

        val before = reads()
        val swept = sweeper.sweep()
        val read = reads() - before

        assertThat(swept).describedAs("가린 표에서 옮겼다 — 아니면 이 시험은 아무것도 안 잰다").isEqualTo(HoldSweeper.BATCH_SIZE)
        assertThat(expiredInTemp()).isEqualTo(HoldSweeper.BATCH_SIZE.toLong())
        assertThat(read).describedAs("한 회가 읽은 행 — 표 크기에 비례하면 PK 를 훑은 것이다").isLessThan(READ_CEILING)
    }

    private fun reads(): Long =
        jdbc.sql(
            """
            select coalesce(seq_tup_read, 0) + coalesce(idx_tup_fetch, 0)
              from pg_stat_xact_all_tables where relid = 'pg_temp.reservation'::regclass
            """,
        ).query(Long::class.java).single()

    private fun expiredInTemp(): Long =
        jdbc.sql("select count(*) from pg_temp.reservation where status = 'expired'").query(Long::class.java).single()

    private companion object {
        const val FINISHED = 300_000
        const val EXPIRED = 50_000
        const val READ_CEILING = 10_000L
    }
}
