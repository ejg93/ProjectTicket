package com.projectticket.ticket

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 좌석 상태를 바꾸는 SQL 이 **조건부 UPDATE** 인가(`concurrency-rules.md` 「좌석 선점 — 조건부 UPDATE」, `G7b`, `G5` 원장 ⑧).
 * ProjectShop `Q30` 의 「SQL 을 글자로 읽는다」를 옮겼다 — SQL 은 문자열이라 컴파일된 클래스에 구조로 안 남고 ArchUnit 이 못 본다.
 *
 * **왜 `status` 조건인가.** 「지금 상태가 X 일 때만 Y 로」가 Read Committed 에서 승자 하나를 만드는 전부다.
 * `where status = …` 가 빠진 `update performance_seat` 는 남이 잡은 좌석을 덮어쓴다 — 흐름 시험은 한 사람씩 돌아서 초록이다.
 * 전에는 독립 리뷰가 눈으로 봤다.
 *
 * **무엇을 못 보나**: 원시 문자열(`"""…"""`) 밖에서 조립한 SQL. 지금 좌석 UPDATE 일곱이 전부 원시 문자열이다.
 *
 * 빠른 레인이다 — `src/main/kotlin` 만 읽는다. main 이 바뀌면 클래스가 바뀌어 `test` 가 다시 돈다.
 */
class SqlTextTest {

    private val statements: List<Pair<Path, String>> =
        Files.walk(Path.of("src/main/kotlin")).use { paths ->
            paths.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .flatMap { path -> RAW_STRING.findAll(Files.readString(path)).map { path to it.groupValues[1] } }
                .filter { (_, sql) -> SEAT_UPDATE.containsMatchIn(sql) }
                .toList()
        }

    @Test
    fun every_seat_update_is_conditional_on_the_current_status() {
        // 바닥을 박는다 — 몇 개가 원시 문자열 밖으로 옮겨 가도 「하나는 읽었다」로 초록이 되지 않게(마무리 12차 독립 리뷰).
        assertThat(statements)
            .describedAs("`update performance_seat` 를 ${SEAT_UPDATES_FLOOR} 개보다 적게 읽었다 — 원시 문자열 밖으로 옮겼으면 이 파서도, 지웠으면 바닥도 같이 고친다")
            .hasSizeGreaterThanOrEqualTo(SEAT_UPDATES_FLOOR)

        val unconditional = statements.filterNot { (_, sql) -> conditionsOnStatus(sql) }.map { (path, _) -> path.fileName.toString() }
        assertThat(unconditional)
            .describedAs("좌석 UPDATE 의 where 에 좌석 `status` 조건이 없다 — 남이 잡은 좌석을 덮어쓴다(concurrency-rules.md, D4)")
            .isEmpty()
    }

    /**
     * `where` 뒤에 좌석 표(별칭 또는 맨 이름)의 `status = …`·`status in (…)` 이 있나.
     * **하위 질의는 걷어내고 본다** — `exists (select … p.status …)`·`in (select … where status = …)` 의 `status` 는 회차·예매의 것이라,
     * 남겨 두면 좌석 조건이 없어도 통과한다(마무리 12차 독립 리뷰).
     */
    private fun conditionsOnStatus(sql: String): Boolean {
        val alias = SEAT_UPDATE.find(sql)?.groupValues?.get(1)?.takeUnless { it.equals("set", ignoreCase = true) }
        val where = withoutSubqueries(sql.substringAfter(WHERE_KEYWORD.find(sql)?.value ?: return false))
        val qualifier = if (alias.isNullOrEmpty()) """(?<![\w.])""" else """(?:(?<![\w.])|\b$alias\.)"""
        return Regex("""(?i)${qualifier}status\s*(?:=|in\s*\()""").containsMatchIn(where)
    }

    /** `select` 로 시작하는 괄호 묶음을 통째로 지운다. 안쪽 괄호까지 세어서 짝을 맞춘다 */
    private fun withoutSubqueries(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            if (text[i] == '(' && SUBQUERY_START.matchesAt(text, i)) {
                var depth = 0
                while (i < text.length) {
                    if (text[i] == '(') depth++
                    if (text[i] == ')') depth--
                    i++
                    if (depth == 0) break
                }
            } else {
                append(text[i++])
            }
        }
    }

    private companion object {
        /** 지금 좌석 UPDATE 는 일곱이다(`PaymentTransition` 둘·`PerformanceCancel`·`RefundTransition`·`HoldSweeper`·`PerformanceClose`·`SeatHold`) */
        const val SEAT_UPDATES_FLOOR = 7

        val RAW_STRING = Regex("\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
        val SEAT_UPDATE = Regex("""(?i)\bupdate\s+performance_seat\b(?:\s+(?:as\s+)?(\w+))?""")
        val WHERE_KEYWORD = Regex("""(?i)\bwhere\b""")
        val SUBQUERY_START = Regex("""(?i)\(\s*select\b""")
    }
}
