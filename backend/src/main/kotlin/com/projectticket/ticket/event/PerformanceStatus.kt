package com.projectticket.ticket.event

/**
 * 회차의 상태. `performance.status` 의 값이고 `performance_status_check`(`V17`)가 같은 목록을 든다(`D14` 「열거값」).
 * 전이는 `performance_status_transition` 트리거가 지킨다 — 여기는 값의 출처만 든다(`I9-4`).
 */
enum class PerformanceStatus {
    /** 등록됐다. 좌석이 복제되기 전이라 관객에게 안 보인다 */
    DRAFT,

    /** 열렸다 — 좌석을 복제하고 정산 정책을 박제했다 */
    OPEN,

    /** 판매가 마감됐다(`sales_close_at`, ADR 0004) */
    CLOSED,

    /** 기획사가 취소했다(`17`) */
    CANCELLED;

    val code: String get() = name.lowercase()
}
