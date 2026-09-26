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
 * 좌석 검사가 읽는 것은 원시 문자열(`"""…"""`) 전부와 `.sql(` 의 리터럴 인자다. **조립한 SQL 은 못 읽는다** —
 * 그래서 조립 자체를 아래 두 시험이 막는다(`G10`, `D9` 「이름 바인딩만」). `sql(` 호출의 인자가 리터럴 하나가 아니거나
 * 리터럴 안에서 허용 밖의 값을 보간하면 「결합했다」. CodeQL 은 도우미가 `StringBuilder` 로 조립해 넘긴 SQL 을 못 잡았다(`46c` 탐침).
 * `JdbcClient` 밖의 입구(`JdbcTemplate` 등)는 안 본다 — 지금 main 에 없다.
 *
 * 빠른 레인이다 — `src/main/kotlin` 만 읽는다. main 이 바뀌면 클래스가 바뀌어 `test` 가 다시 돈다.
 */
class SqlTextTest {

    private val sources: List<Pair<Path, String>> =
        Files.walk(Path.of("src/main/kotlin")).use { paths ->
            paths.asSequence().filter { it.toString().endsWith(".kt") }.map { it to Files.readString(it) }.toList()
        }

    /** 문자열 리터럴의 안쪽. 원시 문자열은 역슬래시가 이스케이프가 아니라 보간을 읽는 법이 다르다 */
    private data class Literal(val body: String, val raw: Boolean)

    /** `sql(` 호출 하나. 인자가 문자열 리터럴 하나가 아니면 [literal] 이 `null` 이다 */
    private data class SqlCall(val path: Path, val where: String, val literal: Literal?)

    private val sqlCalls: List<SqlCall> =
        sources.flatMap { (path, text) ->
            SQL_CALL.findAll(text).map { SqlCall(path, "${path.fileName}:${lineOf(text, it.range.first)}", literalArgument(text, it.range.last + 1)) }
                .toList()
        }

    /** 원시 문자열 전부 + `sql(` 의 일반 리터럴 — 한 줄 `"update performance_seat …"` 도 읽는다(마무리 16차 독립 리뷰) */
    private val statements: List<Pair<Path, String>> =
        (
            sources.flatMap { (path, text) -> RAW_STRING.findAll(text).map { path to it.groupValues[1] }.toList() } +
                sqlCalls.mapNotNull { call -> call.literal?.takeUnless { it.raw }?.let { call.path to it.body } }
            )
            .filter { (_, sql) -> SEAT_UPDATE.containsMatchIn(sql) }

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

    @Test
    fun every_sql_argument_is_one_literal() {
        assertThat(sqlCalls).describedAs("`.sql(` 호출을 못 읽었다 — 부르는 꼴이 바뀌었으면 SQL_CALL 을 고친다").isNotEmpty()

        val built = sqlCalls.filter { it.literal == null }.map { it.where }
        assertThat(built)
            .describedAs("결합했다 — `.sql(` 인자가 문자열 리터럴이 아니다. 값은 이름 바인딩으로 싣는다(coding-rules.md 「SQL」, D9)")
            .isEmpty()
    }

    @Test
    fun a_sql_literal_interpolates_only_fixed_fragments() {
        assertThat(sqlCalls).describedAs("`.sql(` 호출을 못 읽었다 — 부르는 꼴이 바뀌었으면 SQL_CALL 을 고친다").isNotEmpty()

        val source = sources.toMap()
        val interpolated = sqlCalls.flatMap { call ->
            val literal = call.literal ?: return@flatMap emptyList()
            interpolations(literal).filterNot { fixedIn(it, source.getValue(call.path)) }.map { "${call.where} $it" }
        }
        assertThat(interpolated)
            .describedAs("결합했다 — `.sql(` 리터럴 안의 보간이 허용 목록(정렬 조각·같은 파일의 const val) 밖이다. 값은 이름 바인딩으로 싣는다(D9)")
            .isEmpty()
    }

    /** 리터럴 안의 보간 전부. 일반 문자열은 역슬래시 다음 글자를 건너뛰고, 원시 문자열은 이스케이프가 없어 모든 달러를 본다 */
    private fun interpolations(literal: Literal): List<String> {
        val found = mutableListOf<String>()
        val text = literal.body
        var i = 0
        while (i < text.length) {
            val match = if (text[i] == '$') INTERPOLATION.matchAt(text, i) else null
            i = when {
                !literal.raw && text[i] == '\\' -> i + 2
                match != null -> match.range.last + 1
                else -> i + 1
            }
            match?.let { found += it.value }
        }
        return found
    }

    /**
     * 컴파일 때 고정되는 조각인가. **이름 꼴만 믿지 않고 그 파일에서 선언을 찾는다** — 대문자 `val` 에 입력을 담아도 통과하면 안 된다.
     * 셋뿐이다: 원시 문자열의 달러 글자 `${'$'}` · 같은 파일의 `const val` · `OrderBy` 가 허용 표로 만든 `order.sql`(`EventQuery`).
     */
    private fun fixedIn(token: String, file: String): Boolean {
        if (token == DOLLAR_LITERAL) return true
        if (token == ORDER_FRAGMENT) return ORDER_DECLARATION.containsMatchIn(file)
        val name = CONSTANT_NAME.matchEntire(token)?.groupValues?.get(1) ?: return false
        return Regex("""\bconst\s+val\s+$name\b""").containsMatchIn(file)
    }

    /**
     * [from] 부터 문자열 리터럴 하나(`"…"` 또는 `"""…"""`)와 닫는 괄호(뒤 쉼표 허용)가 오면 그 안쪽을, 아니면 `null` 을 준다.
     * 리터럴 뒤에 `+`·`.trimIndent()` 가 붙어도 `null` 이다 — 인자가 리터럴 하나가 아니다.
     */
    private fun literalArgument(text: String, from: Int): Literal? {
        val start = skipBlank(text, from)
        val (body, end) = when {
            text.startsWith(RAW_QUOTE, start) -> {
                val close = text.indexOf(RAW_QUOTE, start + RAW_QUOTE.length).takeIf { it >= 0 } ?: return null
                Literal(text.substring(start + RAW_QUOTE.length, close), raw = true) to close + RAW_QUOTE.length
            }
            text.getOrNull(start) == '"' -> {
                val close = closingQuote(text, start + 1) ?: return null
                Literal(text.substring(start + 1, close), raw = false) to close + 1
            }
            else -> return null
        }
        var next = skipBlank(text, end)
        if (text.getOrNull(next) == ',') next = skipBlank(text, next + 1)
        return body.takeIf { text.getOrNull(next) == ')' }
    }

    /** 일반 문자열의 닫는 따옴표. 역슬래시 다음 글자는 건너뛴다 */
    private fun closingQuote(text: String, from: Int): Int? {
        var i = from
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return null
    }

    private fun skipBlank(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1

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

        const val RAW_QUOTE = "\"\"\""

        /**
         * `JdbcClient.sql(` 부르는 자리 — `jdbc.sql(`·`jdbc.sql (`·`with(jdbc) { sql(` 모두. 이름의 일부(`toSql(`)와 선언(`fun sql(`)은 안 문다.
         * 지금 main 은 전부 `.sql(` 이다(마무리 16차 독립 리뷰가 다른 꼴이 빠져나간다고 짚었다).
         */
        val SQL_CALL = Regex("""(?:\.\s*|(?<![\w.$]))(?<!fun\s)sql\s*\(""")

        /** Kotlin 문자열 보간 — 달러 뒤 이름 또는 중괄호 식. 이스케이프는 [interpolations] 가 가른다 */
        val INTERPOLATION = Regex("""\$(?:\{[^}]*}|[A-Za-z_]\w*)""")

        /** 원시 문자열에서 달러 글자를 쓰는 꼴 — 보간이지만 값이 고정이다 */
        const val DOLLAR_LITERAL = "\${'\$'}"

        /** `EventQuery` 의 정렬 조각. `OrderBy` 가 요청 글자를 허용 표로 걸러 만든다 — 같은 파일에 [ORDER_DECLARATION] 이 있어야 한다 */
        const val ORDER_FRAGMENT = "\${order.sql}"
        val ORDER_DECLARATION = Regex("""\bval\s+order\s*=\s*OrderBy\.""")

        /** `$NAME`·`${NAME}` — 같은 파일에 `const val NAME` 이 있어야 허용한다(`LOCK_TIMEOUT_MS`·`CURRENT_EDITION`) */
        val CONSTANT_NAME = Regex("""\$\{?([A-Z][A-Z0-9_]*)}?""")
    }
}
