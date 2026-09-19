package com.projectticket.ticket.event

import jakarta.validation.Validation
import jakarta.validation.Validator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 구역 이름 목록 검증이 **실제로 도는가**(점검 4차, `D8` 단위 층).
 *
 * 전에는 `List<@Pattern …>` 이라 Hibernate Validator 가 그 제약을 못 봤다 — 위반이 0건이었고,
 * 잘못된 이름이 DB check 까지 가서 500 으로 나갔다. 걸어 둔 것과 도는 것이 다른 자리라 **위반을 직접 센다.**
 *
 * 빠른 레인이다 — 검증기만 띄운다.
 */
class SectionCodesTest {

    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun a_badly_shaped_section_is_rejected() {
        val violations = validator.validate(requestWith(listOf("F1-A", "망가진구역")))

        assertThat(violations.map { it.propertyPath.toString() })
            .describedAs("구역 이름 형식 위반을 아무도 안 잡았다 — 제약이 걸려만 있고 안 도는 자리다")
            .containsExactly("grades[0].sections")
    }

    @Test
    fun well_shaped_sections_pass() {
        assertThat(validator.validate(requestWith(listOf("F1-A", "F12-B")))).isEmpty()
    }

    private fun requestWith(sections: List<String>) = OrganizerEventController.CreateEventRequest(
        organizerId = 1,
        title = "공연",
        grades = listOf(OrganizerEventController.GradeRequest(code = "VIP", price = 1_000, sections = sections)),
    )
}
