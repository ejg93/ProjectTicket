package com.projectticket.ticket.auth

/**
 * 계정의 역할. `account.role` 의 값이고 DB 제약 `account_role_check` 와 같은 목록이다.
 *
 * Spring Security authority 로 `ROLE_<이름>` 이 실린다. 행 단위 스코프(어느 기획사의 공연인가)는 여기 없다 —
 * 그것은 organizer_member(청크 7)를 보는 서비스가 판정한다.
 */
enum class AccountRole {
    AUDIENCE, ORGANIZER, ADMIN;

    /** 저장값. DB 는 소문자다 */
    val code: String get() = name.lowercase()

    val authority: String get() = "ROLE_$name"

    companion object {
        /** 모르는 값이면 터진다. 조용히 기본 역할로 떨어지면 새 역할이 제약에 늘었을 때 그 계정만 이유 없이 권한을 잃는다 */
        fun of(code: String): AccountRole =
            entries.firstOrNull { it.code == code } ?: error("모르는 역할이다: $code")
    }
}
