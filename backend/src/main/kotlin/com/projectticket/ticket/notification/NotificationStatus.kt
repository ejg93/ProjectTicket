package com.projectticket.ticket.notification

/**
 * 알림 한 건이 어떻게 됐나. `notification.status` 의 값이고 `notification_status_check` 가 같은 목록을 든다(`D14` 「열거값」).
 */
enum class NotificationStatus {
    /** 만들어졌고 아직 안 보냈다. 스윕이 이것만 훑는다 */
    PENDING,

    /** 나갔다 */
    SENT,

    /** 보내려다 실패했다. 다시 보낼 수 있는 실패고 재시도는 29 가 든다 */
    FAILED,

    /** 보낼 수 없어 건너뛴다 — 탈퇴한 계정. 다시 해도 같으므로 재시도 대상이 아니다 */
    SKIPPED;

    val code: String get() = name.lowercase()
}
