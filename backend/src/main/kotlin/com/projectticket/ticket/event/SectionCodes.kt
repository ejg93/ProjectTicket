package com.projectticket.ticket.event

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * 구역 이름 목록. **원소마다** 형식을 본다 — `F<층>-<구역>`(`^F[0-9]+-[A-Z]$`).
 *
 * **`List<@Pattern …>` 로 쓰면 조용히 안 돈다**(점검 4차). Kotlin 은 타입 인자에 붙은 제약을
 * 생성자 파라미터의 타입에만 얹어서 Hibernate Validator 가 필드에서 못 찾는다 — 검증이 없는 것과 같다.
 * 그때 잘못된 이름은 앱을 지나 `seat_grade_map_section_format_check`(`V5`)에 걸리고,
 * 제약 위반은 마지막 그물이 받아 **400 이어야 할 것이 500 으로** 나간다.
 *
 * 형식의 출처는 `V4`·`V5` 의 check 다. **여기 정규식이 그쪽과 갈리면 이 검증만 느슨해진다** — 대조는 `I4-1` 이 세운다.
 */
@Constraint(validatedBy = [SectionCodesValidator::class])
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class SectionCodes(
    val message: String = "구역 이름 형식이 아니다",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

/** 빈 목록·null 은 여기서 안 본다 — 그것은 `@NotEmpty` 의 몫이다. 제약 하나가 한 가지만 말해야 오류 문구가 안 섞인다 */
class SectionCodesValidator : ConstraintValidator<SectionCodes, List<String>> {

    override fun isValid(value: List<String>?, context: ConstraintValidatorContext): Boolean =
        value == null || value.all { FORMAT.matches(it) }

    private companion object {
        val FORMAT = Regex("^F[0-9]+-[A-Z]$")
    }
}
