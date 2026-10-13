package com.projectticket.ticket.payment

/**
 * 환불의 상태. `refund.status` 의 값이고 `refund_status_check`(`V10`)가 같은 목록을 든다(`D14` 「열거값」, `I9-4`).
 */
enum class RefundStatus {
    /** 요청했다 — PG 가 아직 안 돌려줬다. `RefundSweeper` 가 다시 민다 */
    REQUESTED,

    /** PG 가 돌려줬다 */
    DONE;

    val code: String get() = name.lowercase()
}
