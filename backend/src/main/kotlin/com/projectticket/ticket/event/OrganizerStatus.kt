package com.projectticket.ticket.event

/**
 * 기획사의 상태. `organizer.status` 의 값이고 `organizer_status_check`(`V4`)가 같은 목록을 든다(`D14` 「열거값」).
 * 정지된 기획사는 공연·회차를 못 올리고 못 연다 — [OrganizerMembership] 이 소속과 같이 본다(`I13-6`).
 */
enum class OrganizerStatus {
    ACTIVE,

    /** 정지됐다. 계정의 `suspended` 와 같은 축이다(`V4` 주석) */
    SUSPENDED;

    val code: String get() = name.lowercase()
}
