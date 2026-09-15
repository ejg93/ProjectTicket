package com.projectticket.ticket.payment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 환불 산수의 경계(`D6`·`D7`). 빠른 레인 — 고정값을 넣는다. **자정 앞뒤 1초**가 이 규약의 진짜 시험대다.
 */
class RefundPolicyTest {

    /** 관람 2026-09-25 19:30 KST */
    private val startsAt: OffsetDateTime = OffsetDateTime.of(2026, 9, 25, 19, 30, 0, 0, KST)

    @Test
    fun days_are_counted_on_the_kst_calendar_not_in_24h_blocks() {
        // 9/15 23:59:59 은 아직 10일 전, 9/16 00:00:00 은 9일 전이다.
        assertThat(RefundPolicy.daysBefore(startsAt, OffsetDateTime.of(2026, 9, 15, 23, 59, 59, 0, KST))).isEqualTo(10)
        assertThat(RefundPolicy.daysBefore(startsAt, OffsetDateTime.of(2026, 9, 16, 0, 0, 0, 0, KST))).isEqualTo(9)
        // 낮 공연이든 저녁 공연이든 같은 날 취소는 같은 N 이다.
        assertThat(RefundPolicy.daysBefore(startsAt, OffsetDateTime.of(2026, 9, 25, 9, 0, 0, 0, KST))).isZero()
    }

    @Test
    fun utc_input_is_converted_before_counting() {
        // 2026-09-15T15:00:00Z 는 KST 로 9/16 00:00 이다. UTC 날짜로 세면 15일이라 10 이 나온다 — 그것이 이 규약을 쓴 이유다.
        assertThat(RefundPolicy.daysBefore(startsAt, OffsetDateTime.of(2026, 9, 15, 15, 0, 0, 0, ZoneOffset.UTC))).isEqualTo(9)
        assertThat(RefundPolicy.daysBefore(startsAt, OffsetDateTime.of(2026, 9, 15, 14, 59, 59, 0, ZoneOffset.UTC))).isEqualTo(10)
    }

    @Test
    fun fee_is_rounded_once_on_the_total_half_up() {
        assertThat(RefundPolicy.fee(154_000, BigDecimal("0.10"))).isEqualTo(15_400)
        assertThat(RefundPolicy.fee(616_000, BigDecimal("0.30"))).isEqualTo(184_800)
        // 0.5 는 올린다(half-up). 5 × 0.10 = 0.5 → 1
        assertThat(RefundPolicy.fee(5, BigDecimal("0.10"))).isEqualTo(1)
        assertThat(RefundPolicy.fee(154_000, BigDecimal.ZERO)).isZero()
    }

    private companion object {
        val KST: ZoneOffset = ZoneOffset.ofHours(9)
    }
}
