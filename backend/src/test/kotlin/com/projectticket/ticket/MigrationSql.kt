package com.projectticket.ticket

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * 마이그레이션 SQL 을 판 순서로 읽고 **주석을 걷어서** 준다(청크 `I4-2`).
 *
 * 걷는 이유는 제약을 읽는 파서들이 주석 안의 `constraint … check (` 를 실물로 읽기 때문이다.
 * 지울 제약을 주석으로 남겨 두면 표와 대조하는 테스트가 **없는 제약을 있다고 본다** — 지금 마이그레이션엔
 * 그런 줄이 없고, 없을 때 걷어 두는 것이 값이다(생기는 날 아무도 이 파서를 다시 안 본다).
 *
 * [AppDbConstraintTest]·[EnumConstraintTest] 가 같이 쓴다.
 */
object MigrationSql {

    /** `V2` 가 `V10` 보다 먼저다. 이름순으로 읽으면 `V10` 이 앞에 와서 `drop` 반영이 뒤집힌다 */
    fun inOrder(root: Path): List<String> =
        Files.walk(root.resolve("backend/src/main/resources/db/migration")).use { paths ->
            paths.asSequence()
                .filter { it.fileName.toString().startsWith("V") && it.toString().endsWith(".sql") }
                // `!!` 의 근거: 바로 위 filter 가 이름이 `V` 로 시작하는 것만 남겼고 이름 규약이 `V<숫자>__` 다.
                // 그 규약을 어긴 파일이 생기면 여기서 서는 것이 맞다 — 판 순서를 못 정하면 `drop` 반영이 뒤집힌다.
                .sortedBy { VERSION.find(it.fileName.toString())!!.groupValues[1].toInt() }
                .map { withoutComments(Files.readString(it)) }
                .toList()
        }

    /**
     * 줄 주석과 블록 주석을 지운다. **문자열 리터럴 안은 안 건드린다** — 검사가 드는 정규식
     * (`'^F[0-9]+-[A-Z]$'` 같은 것)에 주석 기호가 들어갈 수 있고, 지우면 검사 본문이 잘려서
     * 파서가 조용히 다른 것을 읽는다.
     */
    fun withoutComments(sql: String): String = buildString {
        var i = 0
        var quoted = false
        while (i < sql.length) {
            val c = sql[i]
            when {
                quoted -> {
                    append(c)
                    if (c == '\'') quoted = false
                    i++
                }
                c == '\'' -> {
                    append(c)
                    quoted = true
                    i++
                }
                sql.startsWith("--", i) -> while (i < sql.length && sql[i] != '\n') i++
                sql.startsWith("/*", i) -> {
                    val end = sql.indexOf("*/", i + 2)
                    i = if (end < 0) sql.length else end + 2
                    // 주석이 앞뒤 토큰을 붙여 놓지 않게 한 칸 남긴다.
                    append(' ')
                }
                else -> {
                    append(c)
                    i++
                }
            }
        }
    }

    private val VERSION = Regex("""^V(\d+)""")
}
