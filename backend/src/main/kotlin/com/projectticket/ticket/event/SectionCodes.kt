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
 * 형식을 **막는** 자리는 `V4`·`V5` 의 check 다(강제 지점 2위). 글자의 출처는 아래 [SectionCodesValidator] 의 `FORMAT_REGEX` 고,
 * 둘이 갈리면 이 검증만 느슨해져 400 이 500 이 된다 — `AppDbConstraintTest` 가 그 둘을 맞춘다.
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

    /** 글자의 출처. `V4`·`V5` 의 check 와 같아야 한다 — `AppDbConstraintTest` 가 잰다 */
    companion object {
        const val FORMAT_REGEX = "^F[0-9]+-[A-Z]$"
        private val FORMAT = Regex(FORMAT_REGEX)
    }
}
