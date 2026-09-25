package com.projectticket.ticket

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * `naming-rules.md`(`D15`) 「SQL」 표를 **도는 스키마**에 건다(`G7b`, `G5` 원장 ⑪, ProjectShop `Q31` 이식).
 *
 * **마이그레이션 파일을 안 훑는다.** 글자를 훑으면 적힌 것을 보는데 물을 것은 지금 도는 스키마다 — `alter` 가 쌓이면 둘이 갈린다.
 * 원본에서 옮긴 것은 이쪽 `D15` 가 적은 칸뿐이다(원본의 `_no`·`_number`·금액 타입 칸은 여기 규약에 없다).
 *
 * **예외를 이름으로 안 적고 구조로 가른다** — 1:1 로 본체를 늘린 표는 기본키가 곧 본체를 가리키는 외래키라
 * `information_schema` 에 「이 기본키 컬럼이 외래키이기도 한가」를 묻는다. **복합 기본키는 안 잰다.**
 * **단수·복수는 못 본다** — 뜻을 읽어야 정해진다.
 */
class SchemaNamingTest : PostgresTestBase() {

    @Autowired private lateinit var jdbc: JdbcClient

    private data class Column(val table: String, val name: String, val type: String) {
        val qualified get() = "$table.$name"
    }

    @Test
    fun there_are_columns_to_check() {
        // 질의가 틀리면 0개를 재고 아래가 전부 조용히 통과한다.
        assertThat(columns()).describedAs("information_schema 에서 컬럼을 못 읽었다").hasSizeGreaterThan(MIN_COLUMNS)
    }

    @Test
    fun table_and_column_names_are_snake_case() {
        val columns = columns()
        val bad = (columns.map { it.table }.toSet().filterNot { SNAKE.matches(it) } +
            columns.filterNot { SNAKE.matches(it.name) }.map { it.qualified }).sorted()
        assertThat(bad)
            .describedAs("대문자나 다른 글자가 섞이면 따옴표 없이 못 부르는 이름이 생긴다(naming-rules.md 「SQL」)")
            .isEmpty()
    }

    @Test
    fun a_single_primary_key_is_table_id_or_the_parent_key() {
        val primaryKeys = keyColumns("PRIMARY KEY")
        val foreignKeys = keyColumns("FOREIGN KEY").flatMap { (table, cols) -> cols.map { "$table.$it" } }.toSet()

        val wrong = primaryKeys
            .filterValues { it.size == 1 }
            .filter { (table, cols) -> cols.single() != "${table}_id" && "$table.${cols.single()}" !in foreignKeys }
            .map { (table, cols) -> "$table.${cols.single()}" }
        assertThat(wrong)
            .describedAs("기본키는 `<테이블>_id` 다 — 조인이 `a.account_id = b.account_id` 가 된다(naming-rules.md 「기준에서 벗어난 것」)")
            .isEmpty()
    }

    @Test
    fun time_columns_and_their_suffixes_point_at_each_other() {
        // 양방향으로 잰다. `_at` 인데 시각이 아니면 읽는 쪽이 시각으로 다루고, 시각인데 `_at`·`_until` 이 아니면 시각인 줄 모른다.
        val mismatched = columns().filter { column ->
            val isTime = column.type == "timestamp with time zone"
            val timeName = TIME_SUFFIXES.any { column.name.endsWith(it) }
            isTime != timeName
        }.map { "${it.qualified} (${it.type})" }
        assertThat(mismatched)
            .describedAs("시각은 `_at`(일어난 시각)·`_until`(거기까지 유효)으로 끝나고 타입은 timestamptz 다(naming-rules.md 「SQL」)")
            .isEmpty()
    }

    @Test
    fun boolean_columns_start_with_is() {
        assertThat(columns().filter { it.type == "boolean" && !it.name.startsWith("is_") }.map { it.qualified } - NAMED_EXCEPTIONS.keys)
            .describedAs("접두사가 없으면 참일 때 무엇이 참인지가 이름에 안 들어간다(naming-rules.md 「SQL」)")
            .isEmpty()
    }

    @Test
    fun named_exceptions_still_exist() {
        val existing = columns().map { it.qualified }.toSet()
        assertThat(NAMED_EXCEPTIONS.keys - existing)
            .describedAs("없어진 컬럼이 예외 목록에 남으면 무엇을 봐주고 있는지 아무도 모른다. 지웠으면 NAMED_EXCEPTIONS 에서도 지운다")
            .isEmpty()
    }

    private fun columns(): List<Column> =
        jdbc.sql(
            """
            select c.table_name, c.column_name, c.data_type
              from information_schema.columns c
              join information_schema.tables t using (table_schema, table_name)
             where t.table_schema = 'public'
               and t.table_type = 'BASE TABLE'
               and t.table_name <> :flyway
            """,
        )
            .param("flyway", FLYWAY)
            .query { rs, _ -> Column(rs.getString("table_name"), rs.getString("column_name"), rs.getString("data_type")) }
            .list()

    /** 표마다 그 제약이 잡은 컬럼들 */
    private fun keyColumns(constraintType: String): Map<String, List<String>> =
        jdbc.sql(
            """
            select tc.table_name, kcu.column_name
              from information_schema.table_constraints tc
              join information_schema.key_column_usage kcu
                on kcu.constraint_name = tc.constraint_name
               and kcu.constraint_schema = tc.constraint_schema
             where tc.constraint_type = :type
               and tc.table_schema = 'public'
               and tc.table_name <> :flyway
            """,
        )
            .param("type", constraintType)
            .param("flyway", FLYWAY)
            .query { rs, _ -> rs.getString("table_name") to rs.getString("column_name") }
            .list()
            .groupBy({ it.first }, { it.second })

    private companion object {
        /** 이름을 짓는 규칙이 아니라 Flyway 가 만드는 표다 */
        const val FLYWAY = "flyway_schema_history"

        /** `V1`~`V20` 이 만든 컬럼은 이보다 훨씬 많다. 이 밑이면 질의가 틀린 것이다 */
        const val MIN_COLUMNS = 100

        /** 소문자·숫자·밑줄, 끝이 밑줄이 아니다 */
        val SNAKE = Regex("^[a-z][a-z0-9_]*[a-z0-9]$")

        val TIME_SUFFIXES = listOf("_at", "_until")

        /** 이름으로 봐주는 것. 값이 `naming-rules.md` 「기준에서 벗어난 것」의 사유다 — 거기 줄이 없으면 여기 못 넣는다 */
        val NAMED_EXCEPTIONS = mapOf(
            "account_consent.granted" to "불리언은 is_ — V3 가 표·뷰·트리거에 같은 이름으로 걸었다",
        )
    }
}
