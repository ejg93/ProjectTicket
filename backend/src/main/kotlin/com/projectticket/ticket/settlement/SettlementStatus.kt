package com.projectticket.ticket.settlement

/**
 * 정산서의 상태(`D21`). `settlement_status_check` 가 같은 목록을 든다(`D14` 「열거값」).
 */
enum class SettlementStatus {
    /** 회차 종료 사건을 받아 예약됐다. 금액 0, 항목 없음 — 집계는 `settle_at` 뒤다 */
    SCHEDULED,

    /** 집계가 끝났다. 관리자 확인을 기다린다 */
    PENDING,

    /** 관리자가 확인했다 */
    CONFIRMED,

    /** 지급됐다(모의) */
    PAID;

    val code: String get() = name.lowercase()
}

/**
 * 정산 항목의 종류(`D21` 「항목」). **부호는 값이 든다** — `platform_fee` 가 음수고 나머지는 양수다.
 *
 * 종류를 더하는 것이 이 목록에 한 줄이고, 그것이 「무엇으로 바꿔도 표 구조가 안 바뀐다」의 뜻이다.
 */
enum class SettlementLineKind {
    /** 판매된 것 — `reserved` 예매의 좌석 가격 합 */
    SALE,

    /** 플랫폼 몫 — `sale × commission_rate`. **음수** */
    PLATFORM_FEE,

    /** 취소 수수료 중 기획사 몫 — 자리를 비운 손해의 보전(`D6`) */
    CANCEL_FEE,

    /** 위약금·보정. 지금은 안 만든다 — 새 항목 종류 없이 받는 자리다 */
    ADJUSTMENT;

    val code: String get() = name.lowercase()
}
