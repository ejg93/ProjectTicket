package com.projectticket.ticket.payment

/**
 * 결제 결과. `payment.status` 의 값이고 `payment_status_check` 가 같은 목록을 든다(`D14` 「열거값」, `D3` 「결제」).
 *
 * `requested` 가 없는 이유는 PG 호출이 트랜잭션 밖이라 부르기 전에 적을 트랜잭션이 없어서다(`D3`).
 */
enum class PaymentStatus {
    /** 승인. 예매가 `reserved` 로 간다 — 승인이 늦어 좌석을 못 준 것(`payment_late`)도 결제는 승인이다 */
    APPROVED,

    /** PG 가 거절했다(잔액 부족 등). 예매는 `held` 로 돌아간다 */
    DECLINED,

    /** 응답이 없었고 PG 도 모른다. 예매는 `held` 로 돌아간다 — 다시 시도할 수 있다 */
    FAILED;

    val code: String get() = name.lowercase()
}
