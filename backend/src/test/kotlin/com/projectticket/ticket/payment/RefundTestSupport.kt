package com.projectticket.ticket.payment

/**
 * 취소 서비스를 직접 부르는 시험이 싣는 「본 금액」(`44a-1c`). 화면이 미리보기에서 받아 싣는 것과 같은 값이다.
 * 취소할 수 없는 예매(당일·이미 취소)는 금액이 없어 0 — 대조 앞에서 거절되니 무엇을 실어도 같다.
 */
fun RefundQuote.expectedRefundOf(reservationId: Long, accountId: Long): Int =
    preview(reservationId, accountId).refundAmount ?: 0
