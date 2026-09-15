package com.projectticket.ticket.notification

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 알림 문구. **사용자가 읽는 글이라 존댓말이다**(`D17` 「화면 문구는 존댓말이다」) — 개발자가 읽는 글(문서·주석·커밋)의 평서형과 갈린다.
 *
 * **이름을 안 부른다.** 「OO님」을 쓰려면 표시 이름을 본문에 굳혀야 하는데, 본문은 이력이라 안 고치므로 탈퇴 뒤에도 남는다(`D9`).
 * 받는 사람은 자기 메일함에서 보는 것이라 이름이 없어도 누구에게 온 것인지 안다.
 *
 * 관람 일시는 KST 로 적는다(`D7` — 저장은 UTC, 사람이 읽는 자리는 KST).
 */
object NotificationTemplates {

    fun reserved(performance: PerformanceLine, seats: String, totalAmount: Int, ticketNumbers: List<String>): Pair<String, String> =
        "[예매 확정] ${performance.title}" to
            """
            예매가 확정되었습니다.

            공연: ${performance.title}
            일시: ${kst(performance.startsAt)}
            좌석: $seats
            결제 금액: ${money(totalAmount)}
            티켓 번호: ${ticketNumbers.joinToString(", ")}

            공연 당일 티켓 번호를 보여주시면 입장하실 수 있습니다.
            """.trimIndent()

    fun cancelled(performance: PerformanceLine, daysBefore: Int, feeAmount: Int, refundAmount: Int): Pair<String, String> =
        "[예매 취소] ${performance.title}" to
            """
            예매가 취소되었습니다.

            공연: ${performance.title}
            일시: ${kst(performance.startsAt)}
            취소 시점: 관람일 ${daysBefore}일 전
            취소 수수료: ${money(feeAmount)}
            환불 금액: ${money(refundAmount)}

            환불은 결제하신 카드로 처리됩니다.
            """.trimIndent()

    /** 본문에 드는 회차 정보. 소비자가 표에서 읽어 채운다(`D11` — 사건은 식별자만 나른다) */
    data class PerformanceLine(val title: String, val startsAt: OffsetDateTime)

    private fun kst(at: OffsetDateTime): String = at.atZoneSameInstant(KST).format(FORMAT)

    private fun money(amount: Int): String = "%,d원".format(amount)

    private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm")
}
