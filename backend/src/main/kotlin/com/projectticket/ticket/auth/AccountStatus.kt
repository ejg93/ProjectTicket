package com.projectticket.ticket.auth

/**
 * 계정의 업무 상태. `account.status` 의 값이다.
 *
 * 수명과 다르다. 탈퇴는 `deleted_at` 이 답하고 여기는 「지금 쓸 수 있나」만 답한다.
 */
enum class AccountStatus {
    ACTIVE, SUSPENDED;

    val code: String get() = name.lowercase()

    companion object {
        /**
         * 모르는 값이면 터진다. `"active" == status` 식 비교는 모르는 값에 조용히 false 를 줘서
         * 새 상태가 제약에 늘면 그 계정이 이유 없이 로그인만 막힌다 — 오류도 로그도 안 남는다.
         */
        fun of(code: String): AccountStatus =
            entries.firstOrNull { it.code == code } ?: throw IllegalStateException("모르는 계정 상태다: $code")
    }
}
