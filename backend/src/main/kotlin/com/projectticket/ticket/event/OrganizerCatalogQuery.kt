package com.projectticket.ticket.event

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * 기획사 화면이 고를 목록 둘(`45a-1`) — 공연을 올릴 **내 기획사**, 회차를 올릴 **홀**.
 *
 * 홀은 공용이다(`V4` — 공연장·홀·좌석은 기획사 소유가 아니다). 그래서 누구의 것인지 안 가리고 다 낸다.
 * 구역 코드와 좌석 수를 같이 싣는다 — 등급 폼이 「어느 구역을 어느 등급으로」를 이 목록에서 고른다.
 */
@Component
class OrganizerCatalogQuery(private val jdbc: JdbcClient) {

    fun organizers(accountId: Long): List<Organizer> =
        jdbc.sql(
            """
            select o.organizer_id, o.name
              from organizer o
              join organizer_member m on m.organizer_id = o.organizer_id and m.account_id = :account
             order by o.name, o.organizer_id
            """,
        ).param("account", accountId).query(Organizer::class.java).list().filterNotNull()

    fun halls(): List<Hall> {
        val halls = jdbc.sql(
            """
            select h.hall_id, h.name, v.name as venue_name
              from hall h join venue v on v.venue_id = h.venue_id
             order by v.name, h.name
            """,
        ).query(HallRow::class.java).list().filterNotNull()

        val sections = jdbc.sql(
            "select hall_id, section as code, count(*) as seat_count from seat group by hall_id, section order by hall_id, section",
        ).query(SectionRow::class.java).list().filterNotNull().groupBy({ it.hallId }, { Section(it.code, it.seatCount) })

        return halls.map { Hall(it.hallId, it.name, it.venueName, sections[it.hallId].orEmpty()) }
    }

    data class Organizer(val organizerId: Long, val name: String)

    data class Hall(val hallId: Long, val name: String, val venueName: String, val sections: List<Section>)

    data class Section(val code: String, val seatCount: Int)

    private data class HallRow(val hallId: Long, val name: String, val venueName: String)

    private data class SectionRow(val hallId: Long, val code: String, val seatCount: Int)
}
