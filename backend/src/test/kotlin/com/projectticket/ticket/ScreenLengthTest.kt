package com.projectticket.ticket

import com.projectticket.ticket.auth.AuthController.SignupRequest
import com.projectticket.ticket.event.OrganizerEventController.CreateEventRequest
import jakarta.validation.constraints.Size
import java.nio.file.Files
import java.nio.file.Path
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
 * 방향은 한쪽이다(`D8`): **DTO 의 `@Size(max)` 있는 String 필드마다** 짝 폼에 같은 `maxLength` 가 있어야 한다.
 * 폼→DTO 짝은 아래 표다 — 표가 낡으면(폼 파일·필드가 없으면) 「못 읽었다」로 선다.
 * `maxLength` 는 **리터럴 숫자만** 읽는다. 상수를 쓰면 이 시험이 못 읽으니 리터럴로 적는다.
 *
 * 빠른 레인이다. `frontend/src/app` 의 `*-form.tsx` 는 `build.gradle.kts` 의 `inputs.files` 와 `verify-fingerprint.sh`
 * backend 레인에 걸려 있어야 화면만 고친 커밋에서도 돈다.
 */
class ScreenLengthTest {

    private val app: Path = Path.of("..").toAbsolutePath().normalize().resolve("frontend/src/app")

    @Test
    fun every_sized_request_field_has_the_same_max_length_on_its_form() {
        PAIRS.forEach { (form, request) ->
            val path = app.resolve(form)
            assertThat(Files.exists(path)).describedAs("짝 표의 폼이 없다: $form — 옮겼거나 지웠으면 표를 고친다").isTrue()
            val onScreen = maxLengths(Files.readString(path))
            val onServer = sizedFields(request)

            assertThat(onServer).describedAs("${request.simpleName} 에서 `@Size(max)` 필드를 하나도 못 읽었다").isNotEmpty()
            onServer.forEach { (name, max) ->
                assertThat(onScreen)
                    .describedAs("$form 의 `$name` 칸에 `maxLength={$max}` 가 없다 — 서버 ${request.simpleName} 는 ${max}자까지 받는다")
                    .containsEntry(name, max)
            }
        }
    }

    /** `<Field name="x" … maxLength={N} … />` 에서 이름 → N. 이름이 리터럴이 아닌 칸(`grade_code_${i}`)은 안 읽는다 */
    private fun maxLengths(source: String): Map<String, Int> =
        FIELD.findAll(source).mapNotNull { match ->
            val max = MAX_LENGTH.find(match.groupValues[2])?.groupValues?.get(1)?.toInt()
            max?.let { match.groupValues[1] to it }
        }.toMap()

    /** DTO 의 String 필드 중 `@Size(max)` 를 직접 또는 메타 애너테이션으로 든 것 — 이름은 snake_case(`D5` 응답·본문 표기) */
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
        val PAIRS: List<Pair<String, KClass<*>>> = listOf(
            "signup/signup-form.tsx" to SignupRequest::class,
            "organizer/events/new/event-form.tsx" to CreateEventRequest::class,
        )
        val FIELD = Regex("""<Field\s+name="([a-z_]+)"([^>]*)/>""")
        val MAX_LENGTH = Regex("""maxLength=\{(\d+)\}""")
    }
}
