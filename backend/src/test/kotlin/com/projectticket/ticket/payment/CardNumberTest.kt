package com.projectticket.ticket.payment

import jakarta.validation.Validation
import jakarta.validation.Validator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 카드번호 입구가 ISO/IEC 7812 의 12~19 자리를 재는가(점검 5차).
 *
 * 전에는 정규식 하나라 가운데를 구분자로 채운 `1 - - - - - - - - - -2`(두 자리)가 통과했고,
 * 그 값은 게이트웨이의 `require` 에 걸려 400 이 아니라 500 으로 나갔다. **입구에서 자릿수를 센다.**
 *
 * 빠른 레인이다 — 검증기만 띄운다.
 */
class CardNumberTest {

    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    @ParameterizedTest
    @ValueSource(
        strings = [
            // 자릿수가 모자란다. 구분자로 길이만 채운 것이 전에 새던 모양이다
            "1 - - - - - - - - - -2",
            "12-34",
            "12345678901",
            // 스무 자리 — 위로도 닫혀 있다
            "12345678901234567890",
            // 숫자·구분자가 아닌 것
            "4242-4242-4242-424a",
        ],
    )
    fun a_card_number_outside_the_standard_is_rejected(cardNumber: String) {
        assertThat(validator.validate(PaymentController.PayRequest(cardNumber)))
            .describedAs("입구가 안 거르면 게이트웨이의 `require` 가 받고 500 이 된다")
            .isNotEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["4242424242424242", "4242 4242 4242 4242", "4242-4242-4242-4242", "123456789012"])
    fun a_well_shaped_card_number_passes(cardNumber: String) {
        assertThat(validator.validate(PaymentController.PayRequest(cardNumber))).isEmpty()
    }
}
