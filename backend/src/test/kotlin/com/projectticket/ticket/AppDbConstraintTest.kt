package com.projectticket.ticket

import com.projectticket.ticket.auth.AuthController
import com.projectticket.ticket.auth.EmailAddress
import com.projectticket.ticket.event.OrganizerEventController
import com.projectticket.ticket.event.SectionCodesValidator
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 같은 규칙을 앱 검증과 DB 제약이 **두 벌** 드는 자리를 맞춘다(청크 `I4-1`).
 *
 * 두 벌인 것 자체는 맞다 — 앱은 400 을 빨리 주고, DB 는 배치·시드·`psql` 까지 막는 더 낮은 자리다(`D14`).
 * **갈릴 때가 문제다.** 앱이 느슨해진 쪽으로 들어온 값은 검증을 지나 제약에 걸리고, 제약 위반은 마지막 그물이 받아
 * **400 이어야 할 것이 500 으로 나간다** — 점검 4차의 구역 이름이 그 실물이었다.
 *
 * 값 목록(`check (x in …)`)은 [EnumConstraintTest] 가 본다. **여기는 길이와 형식이다.**
 *
 * 빠른 레인이다 — 마이그레이션 파일과 애너테이션만 읽는다.
 */
class AppDbConstraintTest {

    private val root: Path = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun length_limits_match_the_database() {
        val checks = liveChecks()

        LENGTHS.forEach { (constraint, appMax) ->
            val body = bodyOf(checks, constraint)
            val dbMax = LENGTH_BOUND.find(body)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }

            assertThat(dbMax)
                .describedAs("`$constraint` 에서 길이 상한을 못 읽었다. 검사 꼴이 바뀌었으면 이 파서도 같이 고친다")
                .isNotNull()
            assertThat(dbMax?.toInt())
                .describedAs("`$constraint` 와 앱 검증이 갈렸다 — 느슨한 쪽을 지난 값이 400 이 아니라 500 으로 나간다")
                .isEqualTo(appMax)
        }
    }

    @Test
    fun formats_match_the_database() {
        val checks = liveChecks()

        FORMATS.forEach { (constraint, appRegex) ->
            val dbRegex = DB_REGEX.find(bodyOf(checks, constraint))?.groupValues?.get(1)

            assertThat(dbRegex)
                .describedAs("`$constraint` 와 앱 검증의 정규식이 갈렸다 — 앱만 느슨해지면 500 이 나간다")
                .isEqualTo(appRegex)
        }
    }

    @Test
    fun the_price_floor_is_in_both_places() {
        // 음수 금액은 정산·환불이 통째로 뒤집히는 값이다(`D21`). 앱의 `@PositiveOrZero` 와 DB 의 `>= 0` 은 한 규칙이다.
        assertThat(annotationOf<PositiveOrZero>(OrganizerEventController.GradeRequest::class.java, "price"))
            .describedAs("`price` 의 `@PositiveOrZero` 가 사라졌다. DB 만 남으면 음수 요청이 500 으로 나간다")
            .isNotNull()
        assertThat(bodyOf(liveChecks(), "seat_grade_price_check").replace(" ", ""))
            .contains("price>=0")
    }

    private fun bodyOf(checks: Map<String, String>, constraint: String): String {
        assertThat(checks)
            .describedAs("`$constraint` 가 마이그레이션에 없다. 제약 이름이 바뀌었으면 이 표도 바뀐다")
            .containsKey(constraint)
        return checks.getValue(constraint)
    }

    /** 제약 이름 → `check (…)` 안쪽. `drop` 을 반영해 **마지막 판만** 남긴다([EnumConstraintTest] 와 같은 이유) */
    private fun liveChecks(): Map<String, String> = buildMap {
        migrationsInOrder().forEach { sql ->
            DROP.findAll(sql).forEach { remove(it.groupValues[1]) }
            CHECK.findAll(sql).forEach { match ->
                put(match.groupValues[1], balanced(sql, match.range.last))
            }
        }
    }

    /** 여는 괄호에서 시작해 짝이 맞는 자리까지. `length(x) <= 50` 처럼 안에 괄호가 또 있다 */
    private fun balanced(sql: String, openIndex: Int): String {
        var depth = 0
        var quoted = false
        for (i in openIndex until sql.length) {
            val c = sql[i]
            when {
                c == '\'' -> quoted = !quoted
                quoted -> continue
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return sql.substring(openIndex + 1, i)
                }
            }
        }
        return ""
    }

    private fun migrationsInOrder(): List<String> =
        Files.walk(root.resolve("backend/src/main/resources/db/migration")).use { paths ->
            paths.asSequence()
                .filter { it.fileName.toString().startsWith("V") && it.toString().endsWith(".sql") }
                // `!!` 의 근거: 위 filter 가 `V` 로 시작하는 것만 남겼고 이름 규약이 `V<숫자>__` 다.
                .sortedBy { VERSION.find(it.fileName.toString())!!.groupValues[1].toInt() }
                .map(Files::readString)
                .toList()
        }

    private companion object {
        val CHECK = Regex("""constraint\s+(\w+)\s+check\s*\(""")
        val DROP = Regex("""drop\s+constraint\s+(?:if\s+exists\s+)?(\w+)""")
        /** `length(x) <= 50` 과 `length(x) between 1 and 200` 둘 다. 옥텟으로 재는 것도 같은 꼴이다 */
        val LENGTH_BOUND = Regex("""(?:octet_)?length\([\w.]+\)\s*(?:<=\s*(\d+)|between\s+\d+\s+and\s+(\d+))""")
        val DB_REGEX = Regex("""~\s*'([^']*)'""")
        val VERSION = Regex("""^V(\d+)""")

        inline fun <reified T : Annotation> annotationOf(owner: Class<*>, field: String): T? =
            owner.getDeclaredField(field).getAnnotation(T::class.java)

        /**
         * 제약 이름 → 앱이 든 길이 상한.
         *
         * 이메일은 **근사가 의도다** — 앱은 글자로 재고 DB 는 옥텟으로 잰다(`EmailAddress`). 숫자가 같은 것만 본다.
         */
        val LENGTHS: Map<String, Int> = mapOf(
            "account_display_name_length_check" to
                annotationOf<Size>(AuthController.SignupRequest::class.java, "displayName")!!.max,
            "account_email_length_check" to
                EmailAddress::class.java.getAnnotation(Size::class.java).max,
            "event_title_length_check" to
                annotationOf<Size>(OrganizerEventController.CreateEventRequest::class.java, "title")!!.max,
        )

        /** 제약 이름 → 앱이 든 정규식. 구역은 두 표가 같은 형식을 든다(`seat`·`seat_grade_map`) */
        val FORMATS: Map<String, String> = mapOf(
            "seat_grade_code_format_check" to
                annotationOf<Pattern>(OrganizerEventController.GradeRequest::class.java, "code")!!.regexp,
            "seat_section_format_check" to SectionCodesValidator.FORMAT_REGEX,
            "seat_grade_map_section_format_check" to SectionCodesValidator.FORMAT_REGEX,
        )
    }
}
