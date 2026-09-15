package com.projectticket.ticket.reservation

/**
 * 예매의 상태. `reservation.status` 의 값이고 `reservation_status_check` 가 같은 목록을 든다(`D14` 「열거값」).
 * 전이는 `state-machines.md`(D3)가 정하고 `reservation_status_transition` 트리거가 문지기다.
 */
enum class ReservationStatus {
    HELD, PAYING, RESERVED, CANCELLED, EXPIRED;

    val code: String get() = name.lowercase()

    companion object {
        /** 모르는 값이면 터진다. 조용히 넘기면 새 상태가 제약에 늘었을 때 그 예매만 이유 없이 화면에서 빠진다 */
        fun of(code: String): ReservationStatus =
            entries.firstOrNull { it.code == code } ?: throw IllegalStateException("모르는 예매 상태다: $code")
    }
}
