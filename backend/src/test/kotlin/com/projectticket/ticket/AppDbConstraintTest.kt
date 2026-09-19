package com.projectticket.ticket

import com.projectticket.ticket.auth.AuthController
import com.projectticket.ticket.auth.EmailAddress
import com.projectticket.ticket.event.OrganizerEventController
import com.projectticket.ticket.event.SectionCodesValidator
import com.projectticket.ticket.payment.CardNumbers
import com.projectticket.ticket.reservation.TicketService
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 같은 규칙을 앱 검증과 DB 제약이 **두 벌** 드는 자리를 맞춘다(청크 `I4-1`, 역방향은 `I4-2`).
 *
 * 두 벌인 것 자체는 맞다 — 앱은 400 을 빨리 주고, DB 는 배치·시드·`psql` 까지 막는 더 낮은 자리다(`D14`).
 * **갈릴 때가 문제다.** 앱이 느슨해진 쪽으로 들어온 값은 검증을 지나 제약에 걸리고, 제약 위반은 마지막 그물이 받아
 * **400 이어야 할 것이 500 으로 나간다** — 점검 4차의 구역 이름이 그 실물이었다.
 *
 * **방향이 둘이다.** 표에 적은 것이 실물과 같은가(앞)와, 실물에 있는 검사가 표에 있는가(뒤)다.
 * 앞만 보면 새 `check` 를 파고 표에 안 적었을 때 **영영 초록이다** — 그것이 `I4-2` 가 닫은 구멍이다.
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

        LENGTHS.forEach { (constraint, app) ->
            val bound = LENGTH_BOUND.find(bodyOf(checks, constraint))

            assertThat(bound)
                .describedAs("`$constraint` 에서 길이 상한을 못 읽었다. 검사 꼴이 바뀌었으면 이 파서도 같이 고친다")
                .isNotNull()
            // `!!` 의 근거: 바로 위 단언이 null 이면 이미 섰다.
            val groups = bound!!.groupValues
            val dbMax = groups[1].ifEmpty { groups[3] }
            val dbMin = groups[2].takeIf { it.isNotEmpty() }?.toInt()

            assertThat(dbMax.toInt())
                .describedAs("`$constraint` 와 앱 검증이 갈렸다 — 느슨한 쪽을 지난 값이 400 이 아니라 500 으로 나간다")
                .isEqualTo(app.max)

            // **하한도 규칙이다.** DB 가 빈 값을 막는데 앱이 안 막으면 빈 문자열이 검증을 지나 제약에 걸린다.
            if (dbMin != null && dbMin >= 1) {
                assertThat(app.rejectsBlank)
                    .describedAs("`$constraint` 는 하한 $dbMin 을 드는데 앱에 `@NotBlank` 가 없다 — 빈 값이 400 이 아니라 500 이다")
                    .isTrue()
            }
        }
    }

    @Test
    fun formats_match_the_database() {
        val checks = liveChecks()

        FORMATS.forEach { (constraint, appRegex) ->
            assertThat(dbRegexOf(checks, constraint))
                .describedAs("`$constraint` 와 앱 검증의 정규식이 갈렸다 — 앱만 느슨해지면 500 이 나간다")
                .isEqualTo(appRegex)
        }
    }

    @Test
    fun every_length_check_in_the_migrations_is_accounted_for() {
        val inMigrations = liveChecks().filterValues { LENGTH_CALL.containsMatchIn(it) }.keys

        // 길이 check 를 새로 파면 **여기서 선다.** 위 표에 적든 「앱 입구가 없다」고 적든 하나는 해야 지나간다.
        assertThat(inMigrations)
            .describedAs("길이 check 가 생겼는데 앱 쪽 짝도, 짝이 없다는 근거도 없다")
            .isSubsetOf(LENGTHS.keys + ACCOUNTED_ELSEWHERE.keys)
    }

    @Test
    fun every_format_check_in_the_migrations_is_accounted_for() {
        val inMigrations = liveChecks().filterValues { DB_REGEX.containsMatchIn(it) }.keys

        assertThat(inMigrations)
            .describedAs("정규식 check 가 생겼는데 앱 쪽 짝도, 짝이 없다는 근거도 없다")
            .isSubsetOf(FORMATS.keys + ACCOUNTED_ELSEWHERE.keys)
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

    @Test
    fun the_generated_ticket_number_fits_the_check() {
        val shape = Regex(dbRegexOf(liveChecks(), "ticket_ticket_number_format_check"))

        // 티켓 번호는 **서버가 뽑는다** — 입구가 없으니 짝은 뽑는 자리다. 알파벳이나 길이가 갈리면
        // `insert` 가 제약에 걸려 500 이고, 그것은 사용자가 고칠 수 있는 값이 아니다.
        TicketService.ALPHABET.forEach { letter ->
            assertThat(shape.matches("20260919-" + letter.toString().repeat(TicketService.RANDOM_LENGTH)))
                .describedAs("`$letter` 는 `TicketService.ALPHABET` 에 있는데 `ticket_ticket_number_format_check` 가 안 받는다")
                .isTrue()
        }
        assertThat(shape.matches("20260919-" + "2".repeat(TicketService.RANDOM_LENGTH + 1)))
            .describedAs("한 자리가 늘어도 검사가 받는다 — `RANDOM_LENGTH` 와 검사가 갈렸다")
            .isFalse()
    }

    @Test
    fun the_card_entrance_guarantees_the_last4_format() {
        val shape = Regex(dbRegexOf(liveChecks(), "payment_card_last4_format_check"))

        // `card_last4` 는 입구를 지난 번호의 뒤 넷이다. **입구를 ASCII 로 좁힌 근거가 이 검사다**(마무리 8차).
        assertThat(shape.matches(CardNumbers.digitsOf("4111-1111-1111-1111").takeLast(4)))
            .describedAs("입구를 지난 번호의 뒤 넷이 `payment_card_last4_format_check` 를 못 지난다")
            .isTrue()
        // 아라비아-인도 숫자 열여섯. 입구가 받으면 그 값이 `card_last4` 가 되어 이 검사에 걸리고 400 이 500 이 된다.
        assertThat(CardNumbers.isWellShaped("٤" + "١".repeat(15)))
            .describedAs("입구가 유니코드 숫자를 받는다 — `payment_card_last4_format_check` 는 ASCII 만 받는다")
            .isFalse()
    }

    @Test
    fun a_commented_out_check_is_not_read_as_live() {
        // 위 넷이 전부 [MigrationSql] 의 파서 위에 선다. **주석 안의 제약을 실물로 읽으면** 표와의 대조가
        // 없는 제약을 두고 맞다·아니다를 따진다 — 그 반대로 **문자열 안의 `--` 를 주석으로 보면** 검사 본문이 잘린다.
        val stripped = MigrationSql.withoutComments(
            "-- constraint ghost_length_check check (length(x) <= 1)\n" +
                "constraint real_section_format_check check (section ~ '^F--[0-9]+-[A-Z]$')",
        )

        assertThat(stripped)
            .describedAs("주석 안의 제약을 실물로 읽는다")
            .doesNotContain("ghost_length_check")
        assertThat(stripped)
            .describedAs("정규식 안의 `--` 를 주석으로 보고 검사 본문을 잘랐다")
            .contains("'^F--[0-9]+-[A-Z]$'")
    }

    private fun bodyOf(checks: Map<String, String>, constraint: String): String {
        assertThat(checks)
            .describedAs("`$constraint` 가 마이그레이션에 없다. 제약 이름이 바뀌었으면 이 표도 바뀐다")
            .containsKey(constraint)
        return checks.getValue(constraint)
    }

    /** 검사 하나에 정규식이 둘이면 **앞엣것만 보고 지나간다**(`I4-2`). 그 자리에서 세운다 */
    private fun dbRegexOf(checks: Map<String, String>, constraint: String): String {
        val found = DB_REGEX.findAll(bodyOf(checks, constraint)).map { it.groupValues[1] }.toList()
        assertThat(found)
            .describedAs("`$constraint` 의 정규식이 ${found.size} 개다. 이 표는 하나만 드니 검사를 쪼개거나 표를 고친다")
            .hasSize(1)
        return found.single()
    }

    /** 제약 이름 → `check (…)` 안쪽. `drop` 을 반영해 **마지막 판만** 남긴다([EnumConstraintTest] 와 같은 이유) */
    private fun liveChecks(): Map<String, String> = buildMap {
        MigrationSql.inOrder(root).forEach { sql ->
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

    private companion object {
        val CHECK = Regex("""constraint\s+(\w+)\s+check\s*\(""")
        val DROP = Regex("""drop\s+constraint\s+(?:if\s+exists\s+)?(\w+)""")
        val LENGTH_CALL = Regex("""(?:octet_)?length\(""")
        /** `length(x) <= 50` 과 `length(x) between 1 and 200` 둘 다. 옥텟으로 재는 것도 같은 꼴이다 */
        val LENGTH_BOUND = Regex("""(?:octet_)?length\([\w.]+\)\s*(?:<=\s*(\d+)|between\s+(\d+)\s+and\s+(\d+))""")
        val DB_REGEX = Regex("""~\s*'([^']*)'""")

        inline fun <reified T : Annotation> annotationOf(owner: Class<*>, field: String): T? =
            owner.getDeclaredField(field).getAnnotation(T::class.java)

        /** 앱이 든 길이. `rejectsBlank` 는 DB 의 **하한**과 짝이다 — 상한만 맞추면 빈 값이 샌다 */
        data class AppLength(val max: Int, val rejectsBlank: Boolean)

        fun lengthOf(owner: Class<*>, field: String) = AppLength(
            // `!!` 의 근거: 이 표가 든 칸은 `@Size` 를 든 칸이다. 떼면 여기서 서는 것이 맞다.
            max = annotationOf<Size>(owner, field)!!.max,
            rejectsBlank = annotationOf<NotBlank>(owner, field) != null,
        )

        /**
         * 제약 이름 → 앱이 든 길이.
         *
         * 이메일은 **근사가 의도다** — 앱은 글자로 재고 DB 는 옥텟으로 잰다(`EmailAddress`). 숫자가 같은 것만 본다.
         * 그 검사와 표시 이름 검사는 `is null or` 꼴이라 하한이 없다.
         */
        val LENGTHS: Map<String, AppLength> = mapOf(
            "account_display_name_length_check" to
                lengthOf(AuthController.SignupRequest::class.java, "displayName"),
            "account_email_length_check" to
                AppLength(EmailAddress::class.java.getAnnotation(Size::class.java).max, rejectsBlank = false),
            "event_title_length_check" to
                lengthOf(OrganizerEventController.CreateEventRequest::class.java, "title"),
        )

        /** 제약 이름 → 앱이 든 정규식. 구역은 두 표가 같은 형식을 든다(`seat`·`seat_grade_map`) */
        val FORMATS: Map<String, String> = mapOf(
            "seat_grade_code_format_check" to
                annotationOf<Pattern>(OrganizerEventController.GradeRequest::class.java, "code")!!.regexp,
            "seat_section_format_check" to SectionCodesValidator.FORMAT_REGEX,
            "seat_grade_map_section_format_check" to SectionCodesValidator.FORMAT_REGEX,
        )

        /**
         * 위 두 표에 짝이 없는 검사와 그 근거(`I4-2`).
         *
         * **「나중에」가 아니라 근거다.** 입구가 생기면 위 표로 옮긴다 — 안 옮기면 역방향은 지나가도 앞 방향이 안 본다.
         * 여기에도 위에도 없는 검사가 생기면 역방향 둘이 먼저 빨개진다.
         */
        val ACCOUNTED_ELSEWHERE: Map<String, String> = mapOf(
            "ticket_ticket_number_format_check" to "서버가 뽑는다 — 짝은 the_generated_ticket_number_fits_the_check",
            "payment_card_last4_format_check" to "입구를 ASCII 로 좁힌 근거다 — 짝은 the_card_entrance_guarantees_the_last4_format",
            "organizer_code_format_check" to "앱 입구가 없다. `DemoSeeder` 만 넣는다 — 기획사 등록 입구가 생기면 위 표로",
            "seat_row_label_format_check" to "좌석은 `DemoSeeder` 가 넣는다 — 공연장 등록 입구가 생기면 위 표로",
            "idempotency_key_length_check" to "`IdempotencyKeys` 가 UUIDv4 만 받아 36자 고정이다. DB 의 1..255 가 더 넓다",
            "idempotency_key_hash_check" to "SHA-256 hex 를 서버가 잰다. 입구에 없다",
        )
    }
}
