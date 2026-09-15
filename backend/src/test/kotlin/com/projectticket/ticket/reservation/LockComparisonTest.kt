package com.projectticket.ticket.reservation

import com.projectticket.ticket.ConcurrencyTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException

/**
 * 락 방식 셋의 비교 측정(19, ADR 0006). **테스트가 아니라 계측이다** — 결과가 초록·빨강이 아니라 숫자고, `gradlew measure` 로만 돈다(`D8` 「측정」).
 *
 * 같은 좌석 하나를 N 스레드가 동시에 잡는다. 세 방식이 같은 스크래치 표 위에서 같은 조건으로 돈다:
 *
 * | 방식 | 문장 |
 * |---|---|
 * | 조건부 UPDATE | `update … set status='held' where id=? and status='available'` — 한 문장, 갱신 행 수가 판정(`D4`) |
 * | 비관락 | `select … for update` → 앱이 상태를 보고 → `update`. 한 트랜잭션 |
 * | 낙관락 | `select status, version` → `update … where id=? and version=?`. 진 쪽은 0행 |
 *
 * 스크래치 표(`lock_measure_seat`)를 쓰는 이유는 `performance_seat` 에 `version` 이 없어서다 — 측정 때문에 스키마를 안 바꾼다.
 * 재는 것은 「승자 하나」가 아니라(셋 다 하나다) **비용**이다: 전체 소요(스레드 생성 포함)·스레드별 지연 p50/p95. 왕복 수는 안 재고 문장에서 센다.
 * 커넥션 풀은 기본 10 이라 1000 스레드는 풀 앞에서 줄을 선다 — 운영도 그렇다.
 */
@Tag("measure")
class LockComparisonTest : ConcurrencyTestBase() {

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun createScratchTable() {
        jdbc.sql("drop table if exists lock_measure_seat").update()
        jdbc.sql("create table lock_measure_seat (seat_id bigint primary key, status text not null, version int not null default 0)").update()
    }

    @AfterEach
    fun dropScratchTable() {
        jdbc.sql("drop table if exists lock_measure_seat").update()
    }

    @Test
    fun compare_three_strategies() {
        val rows = mutableListOf<Row>()
        for (threads in THREAD_COUNTS) {
            rows += measure("조건부 UPDATE", threads) { conditionalUpdate() }
            rows += measure("비관락 for update", threads) { pessimistic() }
            rows += measure("낙관락 version", threads) { optimistic() }
        }

        val table = render(rows)
        println(table)
        Files.createDirectories(OUTPUT.parent)
        Files.writeString(OUTPUT, table)

        // 계측이지만 이것만은 본다 — 승자가 하나가 아니면 수치가 뜻이 없다.
        assertThat(rows).allSatisfy { assertThat(it.winners).describedAs("${it.strategy} ${it.threads}").isEqualTo(1) }
    }

    /** @return 이 스레드가 좌석을 잡았나 */
    private fun conditionalUpdate(): Boolean =
        jdbc.sql("update lock_measure_seat set status = 'held' where seat_id = :id and status = 'available'")
            .param("id", SEAT).update() == 1

    private fun pessimistic(): Boolean =
        TransactionTemplate(transactionManager).execute {
            val status = jdbc.sql("select status from lock_measure_seat where seat_id = :id for update")
                .param("id", SEAT).query(String::class.java).single()
            if (status != "available") return@execute false
            jdbc.sql("update lock_measure_seat set status = 'held' where seat_id = :id").param("id", SEAT).update()
            true
        }!!  // execute 는 콜백이 null 을 돌려줄 때만 null 이다 — 여기 콜백은 늘 Boolean 을 돌려준다

    private fun optimistic(): Boolean {
        val (status, version) = jdbc.sql("select status, version from lock_measure_seat where seat_id = :id")
            .param("id", SEAT).query { rs, _ -> rs.getString("status") to rs.getInt("version") }.single()
        if (status != "available") return false
        return jdbc.sql("update lock_measure_seat set status = 'held', version = version + 1 where seat_id = :id and version = :version")
            .param("id", SEAT).param("version", version).update() == 1
    }

    private fun measure(strategy: String, threads: Int, attempt: () -> Boolean): Row {
        jdbc.sql("delete from lock_measure_seat").update()
        jdbc.sql("insert into lock_measure_seat (seat_id, status) values (:id, 'available')").param("id", SEAT).update()

        val started = System.nanoTime()
        val results = runConcurrently(threads, timeout = 300) {
            val begun = System.nanoTime()
            val won = attempt()
            won to (System.nanoTime() - begun) / 1_000_000
        }
        val wallMs = (System.nanoTime() - started) / 1_000_000

        val outcomes = results.mapNotNull { it.getOrNull() }
        val latencies = outcomes.map { it.second }.sorted()
        val errors = results.mapNotNull { it.exceptionOrNull() }
        return Row(
            strategy = strategy,
            threads = threads,
            winners = outcomes.count { it.first },
            losers = outcomes.count { !it.first },
            errors = errors.size,
            errorStates = errors.mapNotNull { sqlStateOf(it) }.groupingBy { it }.eachCount(),
            wallMs = wallMs,
            p50Ms = latencies.percentile(50),
            p95Ms = latencies.percentile(95),
            maxMs = latencies.lastOrNull() ?: 0,
        )
    }

    private fun List<Long>.percentile(p: Int): Long = if (isEmpty()) 0 else this[((size - 1) * p) / 100]

    private fun sqlStateOf(e: Throwable): String? =
        generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private fun render(rows: List<Row>): String = buildString {
        appendLine("| 방식 | 스레드 | 승자 | 패자 | 오류(SQLSTATE) | 전체 ms | p50 ms | p95 ms | max ms |")
        appendLine("|---|---|---|---|---|---|---|---|---|")
        rows.forEach {
            val errors = if (it.errors == 0) "0" else "${it.errors}(${it.errorStates.entries.joinToString { (s, n) -> "$s×$n" }})"
            appendLine("| ${it.strategy} | ${it.threads} | ${it.winners} | ${it.losers} | $errors | ${it.wallMs} | ${it.p50Ms} | ${it.p95Ms} | ${it.maxMs} |")
        }
    }

    data class Row(
        val strategy: String,
        val threads: Int,
        val winners: Int,
        val losers: Int,
        val errors: Int,
        val errorStates: Map<String, Int>,
        val wallMs: Long,
        val p50Ms: Long,
        val p95Ms: Long,
        val maxMs: Long,
    )

    companion object {
        private const val SEAT = 1L
        private val THREAD_COUNTS = listOf(100, 1000)
        private val OUTPUT: Path = Path.of("build/measure/lock-comparison.md")
    }
}
