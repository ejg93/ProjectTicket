package com.projectticket.ticket.payment

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * 카드번호 형식. **자릿수는 ISO/IEC 7812 의 12~19 다**(`external-references.md`).
 *
 * 전에는 `@Pattern("[0-9][0-9 -]{10,23}[0-9]")` 하나였는데 가운데를 구분자로 채울 수 있어
 * `1 - - - - - - - - - -2`(두 자리)가 통과했다(점검 5차). 그 값은 게이트웨이의 `require` 에 걸리고,
 * `IllegalArgumentException` 은 마지막 그물이 받아 **400 이어야 할 것이 500 으로** 나갔다.
 *
 * 구분자를 걷어내는 셈은 [CardNumbers] 하나가 든다 — 입구와 게이트웨이가 같은 셈을 써야 한쪽만 느슨해지지 않는다.
 */
@Constraint(validatedBy = [CardNumberValidator::class])
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class CardNumber(
    val message: String = "카드번호 형식이 아니다",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

/** 빈 값은 여기서 안 본다 — `@NotBlank` 의 몫이다 */
class CardNumberValidator : ConstraintValidator<CardNumber, String> {

    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean =
        value == null || CardNumbers.isWellShaped(value)
}

/** 카드번호를 읽는 자리. 사람이 넣는 하이픈·공백을 걷어내고 자릿수를 센다 */
object CardNumbers {

    /** ISO/IEC 7812 — 발급사 식별번호를 포함한 전체 길이 */
    const val MIN_DIGITS = 12
    const val MAX_DIGITS = 19

    /** 하이픈과 공백은 사람이 읽으라고 넣은 것이라 걷어낸다 */
    fun digitsOf(cardNumber: String): String = cardNumber.filter { it != ' ' && it != '-' }

    fun isWellShaped(cardNumber: String): Boolean {
        val digits = digitsOf(cardNumber)
        return digits.length in MIN_DIGITS..MAX_DIGITS && digits.all { it.isDigit() }
    }
}
