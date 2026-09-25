package com.projectticket.ticket

import com.projectticket.ticket.auth.AuthController.SignupRequest
import com.projectticket.ticket.event.OrganizerEventController.CreateEventRequest
import jakarta.validation.constraints.Size
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.reflect.KClass
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 화면 입력칸의 길이 상한이 서버의 `@Size(max)` 와 같은가(`G9`).
 *
 * 갈리면 화면이 받아 준 글자를 서버가 400 으로 돌려보낸다 — 사용자는 51번째 글자를 쳐 놓고서야 안다. 언어가 달라
 * 컴파일이 못 묶어서 **화면 파일을 글자로 읽는다**(`SeatLimitConsistencyTest` 와 같은 모양). 서버 쪽은 리플렉션이다 —
 * `@EmailAddress`·`@Password` 가 `@Size` 를 메타 애너테이션으로 들어서 Kotlin 소스를 정규식으로 읽으면 못 푼다.
 *
 * 방향은 서버 → 화면이다. **DTO 의 `@Size(max)` 있는 String 필드마다** 짝 폼에 같은 `maxLength` 가 있어야 한다 —
 * 화면에만 상한이 있으면 서버가 더 받을 뿐 해가 없다. `*-form.tsx` 는 전부 [PAIRS] 나 [UNSIZED] 에 있어야 한다 —
 * 새 폼이 생기면 어느 쪽인지 적을 때까지 선다. `maxLength` 는 **리터럴 숫자만** 읽는다.
 *
 * 문장이 둘이다 — 「못 읽었다」(칸·꼴을 못 찾음)와 「갈렸다」(찾았는데 수가 다름). 섞으면 정규식 고장이 드리프트로 보인다(`quality-gates.md`).
 *
 * 빠른 레인이다. `*-form.tsx` 는 `build.gradle.kts` 의 `inputs.files` 와 `verify-fingerprint.sh` backend 레인에 걸려 있어야
 * 화면만 고친 커밋에서도 돈다.
 */
class ScreenLengthTest {

    private val app: Path = Path.of("..").toAbsolutePath().normalize().resolve("frontend/src/app")

    @Test
    fun every_form_is_classified() {
        val forms = Files.walk(app).use { paths ->
            paths.filter { it.name.endsWith("-form.tsx") }.map { app.relativize(it).toString().replace('\\', '/') }.toList()
        }
        assertThat(forms).describedAs("못 읽었다 — `frontend/src/app` 에서 `*-form.tsx` 를 하나도 못 찾았다").isNotEmpty()
        assertThat(forms)
            .describedAs("짝 표 밖의 폼이 있다 — 요청 DTO 에 `@Size` String 이 있으면 PAIRS 에, 없으면 UNSIZED 에 이유와 함께 적는다")
            .containsExactlyInAnyOrderElementsOf(PAIRS.keys + UNSIZED.keys)
    }

    @Test
    fun every_sized_request_field_has_the_same_max_length_on_its_form() {
        PAIRS.forEach { (form, request) ->
            val onScreen = fields(Files.readString(app.resolve(form)))
            val onServer = sizedFields(request)
            assertThat(onServer).describedAs("못 읽었다 — ${request.simpleName} 에서 `@Size(max)` 필드를 하나도 못 찾았다").isNotEmpty()

            onServer.forEach { (name, max) ->
                assertThat(onScreen)
                    .describedAs("못 읽었다 — $form 에서 `name=\"$name\"` 인 `<Field … />` 를 못 찾았다(이름이나 꼴이 바뀌었다)")
                    .containsKey(name)
                val raw = onScreen.getValue(name)
                assertThat(raw)
                    .describedAs("갈렸다 — $form 의 `$name` 칸에 `maxLength` 가 없다. 서버 ${request.simpleName} 는 ${max}자까지 받는다")
                    .isNotNull()
                assertThat(raw)
                    .describedAs("못 읽었다 — $form 의 `$name` 칸 `maxLength={$raw}` 가 리터럴 숫자가 아니다")
                    .matches("\\d+")
                assertThat(raw?.toInt())
                    .describedAs("갈렸다 — $form 의 `$name` 칸은 `maxLength={$raw}`, 서버 ${request.simpleName} 는 ${max}자까지 받는다")
                    .isEqualTo(max)
            }
        }
    }

    /** `<Field … name="x" … maxLength={N} … />` 에서 이름 → `maxLength` 글자(없으면 null). 주석 안의 칸은 뺀다 */
    private fun fields(source: String): Map<String, String?> {
        val code = LINE_COMMENT.replace(BLOCK_COMMENT.replace(source, ""), "")
        return FIELD.findAll(code).mapNotNull { match ->
            val attributes = match.groupValues[1]
            NAME.find(attributes)?.groupValues?.get(1)?.let { it to MAX_LENGTH.find(attributes)?.groupValues?.get(1) }
        }.toMap()
    }

    /** DTO 의 String 필드 중 `@Size(max)` 를 직접 또는 메타 애너테이션으로 든 것 — 이름은 snake_case(`D5` 본문 표기) */
    private fun sizedFields(request: KClass<*>): Map<String, Int> =
        request.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { field ->
                val size = field.getAnnotation(Size::class.java)
                    ?: field.annotations.firstNotNullOfOrNull { it.annotationClass.java.getAnnotation(Size::class.java) }
                size?.takeIf { it.max < Int.MAX_VALUE }?.let { snake(field.name) to it.max }
            }
            .toMap()

    private fun snake(name: String) = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

    private companion object {
        /** 폼 → 그 폼이 보내는 요청 DTO. DTO 에 `@Size(max)` String 이 있는 폼만 */
        val PAIRS: Map<String, KClass<*>> = mapOf(
            "signup/signup-form.tsx" to SignupRequest::class,
            "organizer/events/new/event-form.tsx" to CreateEventRequest::class,
        )

        /** 보내는 DTO 에 `@Size(max)` String 이 없는 폼 — 이유를 같이 적는다 */
        val UNSIZED: Map<String, String> = mapOf(
            "login/login-form.tsx" to "LoginRequest 는 @NotBlank 뿐",
            "me/withdraw/withdraw-form.tsx" to "WithdrawRequest 는 @NotBlank 뿐",
            "me/reservations/cancel-form.tsx" to "본문 없음",
            "organizer/events/[eventId]/performance-form.tsx" to "CreatePerformanceRequest 에 String 이 없다",
            "performances/[performanceId]/hold-form.tsx" to "좌석 번호 목록",
            "reservations/[reservationId]/checkout-form.tsx" to "PayRequest.cardNumber 는 @CardNumber(자릿수)라 @Size 가 아니다",
        )

        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""(?m)^\s*//.*$""")
        val FIELD = Regex("""<Field\b(.*?)/>""", RegexOption.DOT_MATCHES_ALL)
        val NAME = Regex("""\bname="([a-z_]+)"""")
        val MAX_LENGTH = Regex("""\bmaxLength=\{([^}]*)\}""")
    }
}
