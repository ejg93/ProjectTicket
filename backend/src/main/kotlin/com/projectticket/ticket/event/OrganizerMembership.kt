package com.projectticket.ticket.event

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * 「이 계정이 그 기획사의 것을 만질 수 있나」. 역할(`ROLE_ORGANIZER`)은 경로 규칙이 이미 봤고(`SecurityConfig`), 여기는 **행 단위 스코프**다.
 *
 * **남의 것은 없는 것이다** — 404 다(`D5` 「403 이냐 404 냐」). 403 을 주면 다른 기획사의 미공개 공연이 존재한다는 사실이 샌다.
 *
 * **없는 것과 남의 것이 같은 `type` 으로 나가야 한다.** 404 를 둘 다 줘도 이름이 `performance-not-found` 와 `event-not-found` 로 갈리면
 * 번호를 훑어 「그 회차는 있다」를 알아낼 수 있다 — 404 로 가린 것을 `type` 이 도로 연다. 그래서 입구마다 자기 이름 하나로 모은다.
 *
 * 기획사는 **요청이 지명한다**(사용자 선택). `organizer_member` 가 다대다라 한 계정이 여럿에 속할 수 있고, 설계가 그것을 이미 허용했다 —
 * 소속을 조회해 하나라고 가정하면 여럿이 생기는 날 이 입구가 멈춘다.
 */
@Component
class OrganizerMembership(private val jdbc: JdbcClient) {

    /** @throws TicketException 그 기획사가 없거나 내 소속이 아니면 404 */
    fun require(accountId: Long, organizerId: Long) {
        if (!isMine(accountId, organizerId)) {
            throw TicketException(ErrorCode.ORGANIZER_NOT_FOUND, "그 기획사가 없거나 내 소속이 아니다: organizer_id=$organizerId")
        }
    }

    /** 그 공연이 내 기획사의 것인가. @return 공연의 기획사 id */
    fun requireEvent(accountId: Long, eventId: Long): Long {
        val organizerId = jdbc.sql("select organizer_id from event where event_id = :id")
            .param("id", eventId)
            .query(Long::class.java)
            .optional()
            .orElse(null)
        // 없는 공연과 남의 공연이 한 이름이다.
        if (organizerId == null || !isMine(accountId, organizerId)) throw TicketException(ErrorCode.EVENT_NOT_FOUND)
        return organizerId
    }

    /** 그 회차가 내 기획사의 것인가 */
    fun requirePerformance(accountId: Long, performanceId: Long) {
        val organizerId = jdbc.sql(
            "select e.organizer_id from performance p join event e on e.event_id = p.event_id where p.performance_id = :id",
        )
            .param("id", performanceId)
            .query(Long::class.java)
            .optional()
            .orElse(null)
        // 없는 회차와 남의 회차가 한 이름이다 — `event-not-found` 로 새면 그 회차의 존재가 드러난다.
        if (organizerId == null || !isMine(accountId, organizerId)) throw TicketException(ErrorCode.PERFORMANCE_NOT_FOUND)
    }

    private fun isMine(accountId: Long, organizerId: Long): Boolean =
        jdbc.sql("select count(*) from organizer_member where organizer_id = :organizer and account_id = :account")
            .param("organizer", organizerId)
            .param("account", accountId)
            .query(Long::class.java)
            .single() > 0L
}
