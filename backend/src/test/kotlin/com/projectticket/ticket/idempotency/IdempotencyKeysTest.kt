package com.projectticket.ticket.idempotency

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * `Idempotency-Key` 헤더의 형식(`D4` 「멱등키」 — UUIDv4, RFC 9562). 빠른 레인.
 *
 * 전에는 선점·결제 흐름 시험(컨테이너 레인)만 이 검사를 거쳐서 빠른 레인의 변이 시험이 **하나도 못 덮었다**(`G4`).
 */
class IdempotencyKeysTest {

    @ParameterizedTest
    @ValueSource(strings = ["3f2b8c1e-9d4a-4b7e-8a61-0c5d2e9f7b13", "  3F2B8C1E-9D4A-4B7E-BA61-0C5D2E9F7B13  "])
    fun a_v4_key_passes_trimmed_and_lowercased(header: String) {
        assertThat(IdempotencyKeys.require(header)).isEqualTo(header.trim().lowercase())
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = [
            "",
            // 판이 1 이다(셋째 마디가 `1`)
            "3f2b8c1e-9d4a-1b7e-8a61-0c5d2e9f7b13",
            // 변형 비트가 틀렸다(넷째 마디가 `c`)
            "3f2b8c1e-9d4a-4b7e-ca61-0c5d2e9f7b13",
            // 하이픈 없는 32 자리
            "3f2b8c1e9d4a4b7e8a610c5d2e9f7b13",
            "not-a-key",
        ],
    )
    fun anything_else_is_a_validation_failure(header: String?) {
        assertThatThrownBy { IdempotencyKeys.require(header) }
            .isInstanceOfSatisfying(TicketException::class.java) { assertThat(it.code).isEqualTo(ErrorCode.VALIDATION_FAILED) }
    }
}
