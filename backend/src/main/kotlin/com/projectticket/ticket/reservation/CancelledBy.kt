package com.projectticket.ticket.reservation

/**
 * 무엇이 예매를 물렀나. `reservation.cancelled_by` 의 값이고 `reservation_cancelled_by_check` 가 같은 목록을 든다(`D14` 「열거값」).
 *
 * **상태로는 못 가른다.** 관객이 먼저 무른 예매와 회차 취소로 물린 예매가 둘 다 `cancelled` 고,
 * 앞쪽은 수수료를 뗀 부분 환불이라 알림·정산·조회가 말을 다르게 해야 한다(26a).
 *
 * `cancelled` 와 `expired` 로 갈 때 **전이 트리거가 이 값을 요구한다** — 새 경로가 생겨도 빠뜨릴 수가 없다.
 */
enum class CancelledBy {
    /** 관객이 취소했다. 취소 수수료가 붙는다(`D6`) */
    AUDIENCE,

    /** 기획사가 회차를 취소했다. 관객 잘못이 아니라 전액이다 */
    ORGANIZER,

    /** 시간이 지나 풀렸다 — 선점 만료·결제 타임아웃·판매 마감 */
    EXPIRED;

    val code: String get() = name.lowercase()
}
