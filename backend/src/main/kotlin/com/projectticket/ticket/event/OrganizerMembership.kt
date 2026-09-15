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
 * 기획사는 **요청이 지명한다**(사용자 선택). `organizer_member` 가 다대다라 한 계정이 여럿에 속할 수 있고, 설계가 그것을 이미 허용했다 —
 * 소속을 조회해 하나라고 가정하면 여럿이 생기는 날 이 입구가 멈춘다.
 */
@Component
class OrganizerMembership(private val jdbc: JdbcClient) {

    /** @throws TicketException 그 기획사가 없거나 내 소속이 아니면 404 */
    fun require(accountId: Long, organizerId: Long) {
        val mine = jdbc.sql("select count(*) from organizer_member where organizer_id = :organizer and account_id = :account")
            .param("organizer", organizerId)
            .param("account", accountId)
            .query(Long::class.java)
            .single()
        if (mine == 0L) throw TicketException(ErrorCode.EVENT_NOT_FOUND, "그 기획사가 없거나 내 소속이 아니다: organizer_id=$organizerId")
    }

    /** 그 공연이 내 기획사의 것인가. @return 공연의 기획사 id */
    fun requireEvent(accountId: Long, eventId: Long): Long {
        val organizerId = jdbc.sql("select organizer_id from event where event_id = :id")
            .param("id", eventId)
            .query(Long::class.java)
            .optional()
            .orElseThrow { TicketException(ErrorCode.EVENT_NOT_FOUND) }
        require(accountId, organizerId)
        return organizerId
    }

    /** 그 회차가 내 기획사의 것인가 */
    fun requirePerformance(accountId: Long, performanceId: Long) {
        val eventId = jdbc.sql("select event_id from performance where performance_id = :id")
            .param("id", performanceId)
            .query(Long::class.java)
            .optional()
            .orElseThrow { TicketException(ErrorCode.PERFORMANCE_NOT_FOUND) }
        requireEvent(accountId, eventId)
    }
}
