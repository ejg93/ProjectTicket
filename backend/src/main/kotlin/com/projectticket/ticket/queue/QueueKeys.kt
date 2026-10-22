package com.projectticket.ticket.queue

/**
 * 대기열이 쓰는 Redis 키(`D12` 「자료구조」). 회차 단위다.
 *
 * 한 곳에 모으는 이유는 **회차가 끝날 때 통째로 지워야** 해서다 — 키 이름이 서비스마다 흩어져 있으면
 * 하나를 빠뜨리고, 빠뜨린 키는 아무 오류도 안 내면서 다음 오픈까지 남는다.
 */
object QueueKeys {
    /** 토큰 키의 접두. 스크립트가 이름을 만들 때 쓴다 */
    const val ADMISSION_PREFIX = "admit:"

    /** 대기 줄. member = accountId, score = 진입 시각(ms) */
    fun waiting(performanceId: Long): String = "queue:$performanceId"

    /** accountId → 마지막 하트비트(ms). 쓰는 것은 24 다 */
    fun seen(performanceId: Long): String = "queue:$performanceId:seen"

    /** 활성 토큰. member = token, score = 만료 시각(ms). 쓰는 것은 22 다 */
    fun active(performanceId: Long): String = "queue:$performanceId:active"

    /**
     * 입장 예산의 창(HASH `start`·`used`, ms 는 Redis `TIME`). 입장 스크립트가 이 값으로 **한 주기에 배치 크기만큼**을 지킨다(`35f`) —
     * 락은 겹침만 막아서, 이것이 없으면 대마다 5초에 한 번씩 돌아 입장 속도가 대수배가 된다. 스케줄러와 줄이 빈 때의 즉시 입장이
     * **같은 예산을 나눠 쓴다** — 즉시 입장만 따로 두면 하나씩 고르게 오는 도착을 R 과 무관하게 다 들인다(마무리 21차 독립 리뷰).
     */
    fun admitWindow(performanceId: Long): String = "queue:$performanceId:admit_window"

    /** 발급된 토큰 하나. 값은 `{account_id, performance_id, issued_at}` 이고 관문(23)이 그것을 대조한다 */
    fun admission(token: String): String = "$ADMISSION_PREFIX$token"

    /** 그 계정의 현재 토큰. 재진입이 자리를 안 잃는 것은 이 키 덕이다 */
    fun admissionByAccount(performanceId: Long, accountId: Long): String =
        "${admissionByAccountPrefix(performanceId)}$accountId"

    /** 입장 스크립트가 계정마다 키를 만들어야 해서 접두로 넘긴다(`AdmissionService`) */
    fun admissionByAccountPrefix(performanceId: Long): String = "admit:by-account:$performanceId:"

    /**
     * 회차 하나가 쓰는 키 전부.
     *
     * `admit:{token}`·`admit:by-account:…` 는 여기 없다 — 토큰 키는 회차로 훑을 수가 없고 TTL 10분이 지운다(`D12`).
     * 남아 있어도 선점은 회차 상태를 다시 보므로(13) 닫힌 회차의 토큰으로는 아무것도 못 한다.
     */
    fun ofPerformance(performanceId: Long): List<String> =
        listOf(waiting(performanceId), seen(performanceId), active(performanceId), admitWindow(performanceId))
}
