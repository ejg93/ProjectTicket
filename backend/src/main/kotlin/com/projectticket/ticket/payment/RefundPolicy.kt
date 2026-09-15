package com.projectticket.ticket.payment

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 환불 계산의 순수 함수(`D6`). 입력을 받아 답만 낸다 — DB 행을 판정하지 않고 시계를 안 든다(`D7` 「앱의 `Clock` 은 계산기에만」).
 * 그래서 단위 테스트가 고정값을 넣는다(`RefundPolicyTest`, 자정 앞뒤 1초).
 *
 * 구간(율)을 고르는 것은 여기가 아니라 SQL 이다 — 구간표가 행이라서(`refund_fee_tier`).
 */
object RefundPolicy {

    /** 업무 판단은 KST 달력이다(`D7`). 서머타임이 없어 하루가 늘 24시간이다 */
    val KST: ZoneId = ZoneId.of("Asia/Seoul")

    /**
     * 「관람일 N일 전」. **시각이 아니라 날짜로 센다** — 24시간 단위로 세면 저녁 공연과 낮 공연의 경계가 갈린다.
     * 경계는 KST 자정이다. `at time zone` 을 빼면 한국 시각 0~9시의 취소가 전날로 잡힌다.
     */
    fun daysBefore(startsAt: OffsetDateTime, cancelledAt: OffsetDateTime): Int =
        ChronoUnit.DAYS.between(
            cancelledAt.atZoneSameInstant(KST).toLocalDate(),
            startsAt.atZoneSameInstant(KST).toLocalDate(),
        ).toInt()

    /** 예매 합계에 한 번, 원 단위 half-up(`D6` 「한 번 반올림」). 좌석마다 매기면 율이 0.125 같은 값일 때 합이 갈린다 */
    fun fee(amount: Int, rate: BigDecimal): Int =
        BigDecimal(amount).multiply(rate).setScale(0, RoundingMode.HALF_UP).intValueExact()
}
