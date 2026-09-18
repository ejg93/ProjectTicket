package com.projectticket.ticket.auth

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * 흔한 비밀번호를 거절한다(`3b`, NIST SP 800-63B §3.1.1.2 의 SHALL).
 *
 * **길이 규칙만으로는 이 SHALL 을 못 지킨다.** 최소 15자라 목록의 짧은 비밀번호는 애초에 안 들어오지만,
 * `passwordpassword`·`123456789012345` 처럼 **흔한 것을 늘려 만든 것**은 길이를 통과한다. 그것이 여기서 막는 것이다.
 *
 * 네 가지를 본다:
 *
 * | 무엇 | 예 | 근거 |
 * |---|---|---|
 * | 목록에 그대로 있다 | `qwertyuiopasdfg` | 유출 목록 대조(SHALL) |
 * | 목록 단어를 반복했다 | `passwordpassword` | 「사전 단어」와 같은 것이다 — 두 번 썼을 뿐이다 |
 * | 한 글자·연속 문자 | `aaaaaaaaaaaaaaa`·`123456789012345` | SHALL 이 「repetitive or sequential」을 든다 |
 * | 서비스 문맥 단어 | `projectticket2026` | SHALL 이 「context-specific words」를 든다 |
 *
 * 목록은 `security/common-passwords.txt`(SecLists 의 10k-most-common). **오프라인 파일이다** —
 * 가입마다 남의 API 를 부르면 그 API 가 죽을 때 가입이 멈추고, 비밀번호가 밖으로 나간다.
 */
@Component
class PasswordBlocklist {

    private val log = LoggerFactory.getLogger(PasswordBlocklist::class.java)

    /** 기동 때 한 번 읽는다. 1만 줄이라 메모리는 1MB 가 안 되고, 조회는 해시 한 번이다 */
    private val common: Set<String> = ClassPathResource(LIST_PATH).inputStream
        .bufferedReader()
        .useLines { lines -> lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet() }
        .also { log.info("흔한 비밀번호 목록 {}개", it.size) }

    /** 사전 단어로 쓸 만한 길이의 것만. 두 글자짜리까지 반복 검사에 쓰면 멀쩡한 비밀번호가 걸린다 */
    private val words: Set<String> = common.filter { it.length >= MIN_WORD_LENGTH }.toSet()

    /** @return 거절할 이유. 통과면 null */
    fun rejection(password: String): String? {
        val normalized = password.trim().lowercase()

        return when {
            normalized in common -> "유출 목록에 있는 비밀번호다"
            isRepeatedWord(normalized) -> "흔한 단어를 반복한 비밀번호다"
            isSingleCharacter(normalized) -> "같은 글자만으로 만든 비밀번호다"
            isSequential(normalized) -> "연속된 문자로 만든 비밀번호다"
            CONTEXT_WORDS.any { it in normalized } -> "서비스 이름이 들어간 비밀번호다"
            else -> null
        }
    }

    /** 이메일에서 따온 비밀번호도 문맥 단어다(SHALL). 입구가 이메일을 아는 자리에서만 부를 수 있다 */
    fun mentionsAccount(password: String, email: String): Boolean {
        val local = email.substringBefore('@').lowercase()
        return local.length >= MIN_WORD_LENGTH && local in password.lowercase()
    }

    /** `passwordpassword` — 앞 조각이 목록 단어고 그것만 반복하면 사전 단어 하나와 다를 것이 없다 */
    private fun isRepeatedWord(password: String): Boolean =
        (MIN_WORD_LENGTH..password.length / 2).any { size ->
            val unit = password.take(size)
            unit in words && password == unit.repeat(password.length / size) + unit.take(password.length % size)
        }

    private fun isSingleCharacter(password: String): Boolean = password.toSet().size == 1

    /** `123456789012345`·`abcdefghijklmno` — 한 칸씩 오르거나 내리기만 한다. 자리 수가 넘어가는 것도 연속으로 본다 */
    private fun isSequential(password: String): Boolean {
        if (password.length < MIN_WORD_LENGTH) return false
        val steps = password.zipWithNext { a, b -> b.code - a.code }
        return steps.all { it == UP } || steps.all { it == DOWN } || steps.all { it == UP || it == DIGIT_WRAP }
    }

    companion object {
        private const val LIST_PATH = "security/common-passwords.txt"
        private const val MIN_WORD_LENGTH = 4

        /** 한 칸 오름·내림. `123…`·`cba…` 가 이 걸음이다 */
        private const val UP = 1
        private const val DOWN = -1

        /** `9` 다음 `0`. 숫자만으로 길게 만들면 이 자리에서 한 번 접힌다 — 그것도 연속이다 */
        private const val DIGIT_WRAP = -9

        /** 이 서비스를 가리키는 말. 목록과 달리 우리가 안다 — 그래서 목록이 아니라 코드에 둔다 */
        private val CONTEXT_WORDS = listOf("projectticket", "ticket", "interpark", "yemae")
    }
}

/**
 * [PasswordBlocklist] 를 제약으로 건다. [Password] 가 이 규칙까지 포함하므로 **입구는 그 애너테이션 하나**다.
 *
 * DB 로는 못 내린다 — 저장되는 것이 해시라 무엇이었는지 알 수가 없다(`D9`). 그래서 강제 지점이 앱 검증이다.
 */
@Constraint(validatedBy = [NoCommonPasswordValidator::class])
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.PROPERTY,
    // [Password] 가 이것을 품는다 — 구성 제약이라 애너테이션 자리에도 붙을 수 있어야 한다.
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class NoCommonPassword(
    val message: String = "흔하거나 규칙적인 비밀번호다. 다른 것을 쓴다",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

/**
 * 거절 이유를 **응답에 안 싣는다**. 「유출 목록에 있다」는 말은 공격자에게 그 목록을 확인해 주는 것이고,
 * 사용자에게 필요한 답은 「다른 것을 쓰라」 하나다(`D9`). 이유는 로그로만 남는다(`D10` — 비밀번호는 안 찍는다).
 */
class NoCommonPasswordValidator(private val blocklist: PasswordBlocklist) : ConstraintValidator<NoCommonPassword, String> {

    private val log = LoggerFactory.getLogger(NoCommonPasswordValidator::class.java)

    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true

        val rejection = blocklist.rejection(value) ?: return true
        log.debug("비밀번호 거절 이유={}", rejection)
        return false
    }
}
