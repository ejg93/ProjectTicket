package com.projectticket.ticket.event

/**
 * 회차별 좌석의 상태. `performance_seat.status` 의 값이고 `performance_seat_status_check` 가 같은 목록을 든다(`D14` 「열거값」).
 *
 * [letter] 는 좌석 현황 응답(`D20`)이 쓰는 한 글자다 — 2천 석 × 폴링이라 필드 값 길이가 곧 대역폭이다.
 */
enum class PerformanceSeatStatus(val letter: String) {
    AVAILABLE("A"), HELD("H"), RESERVED("R");

    val code: String get() = name.lowercase()

    companion object {
        /** 모르는 값이면 터진다. 조용히 `A` 로 떨어뜨리면 새 상태가 제약에 늘었을 때 그 좌석이 빈자리로 보인다 */
        fun of(code: String): PerformanceSeatStatus =
            entries.firstOrNull { it.code == code } ?: error("모르는 좌석 상태다: $code")
    }
}
