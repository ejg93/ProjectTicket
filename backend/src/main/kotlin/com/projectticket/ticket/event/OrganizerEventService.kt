package com.projectticket.ticket.event

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * 기획사가 공연과 회차를 만든다(11).
 *
 * **공연과 등급이 한 트랜잭션이다.** 등급 없는 공연은 회차를 열 수 없고(`PerformanceOpenService` 가 막는다),
 * 그 상태를 나중에 알아볼 방법이 없다 — 「등급을 빠뜨린 공연」과 「등급을 아직 안 넣은 공연」이 같아 보인다.
 *
 * 구역 매핑도 같이 받는다. 등급은 공연 단위고 매핑은 그 등급이 어느 구역에 붙나라, **둘을 가르면 반쪽 공연이 생긴다.**
 */
@Service
class OrganizerEventService(
    private val jdbc: JdbcClient,
    private val auditLog: AuditLog,
    private val membership: OrganizerMembership,
) {

    /** @param sections 이 등급이 붙는 구역들. 같은 구역을 두 등급이 가리키면 `seat_grade_map` 의 기본키가 막는다 */
    data class GradeCommand(val code: String, val price: Int, val sections: List<String>)

    data class EventCommand(val organizerId: Long, val title: String, val grades: List<GradeCommand>)

    data class PerformanceCommand(
        val hallId: Long,
        val startsAt: OffsetDateTime,
        val salesOpenAt: OffsetDateTime,
        /** 안 주면 `starts_at - 1시간` 을 트리거가 채운다(`V14`·`D7`) */
        val salesCloseAt: OffsetDateTime?,
    )

    @Transactional
    fun createEvent(accountId: Long, command: EventCommand): Long {
        membership.require(accountId, command.organizerId)

        val eventId = jdbc.sql("insert into event (organizer_id, title) values (:organizer, :title) returning event_id")
            .param("organizer", command.organizerId)
            .param("title", command.title)
            .query(Long::class.java)
            .single()

        command.grades.forEach { grade ->
            val gradeId = insertGrade(eventId, grade)
            grade.sections.forEach { section -> mapSection(eventId, section, gradeId) }
        }

        auditLog.record(
            AuditLog.Kind.OUTCOME,
            "event.created",
            accountId,
            AuditLog.Target.of("event", eventId),
            mapOf("organizer_id" to command.organizerId, "grade_count" to command.grades.size),
        )
        return eventId
    }

    @Transactional
    fun createPerformance(accountId: Long, eventId: Long, command: PerformanceCommand): Long {
        membership.requireEvent(accountId, eventId)

        // 판매 창(`sales_open_at < sales_close_at < starts_at`)은 `performance_sales_window_check` 가 본다 —
        // 여기서 다시 세지 않는다. 값 검사를 두 군데 두면 하나가 낡는다.
        val performanceId = try {
            jdbc.sql(
                """
                insert into performance (event_id, hall_id, starts_at, sales_open_at, sales_close_at)
                values (:event, :hall, :startsAt, :salesOpenAt, :salesCloseAt)
                returning performance_id
                """,
            )
                .param("event", eventId)
                .param("hall", command.hallId)
                .param("startsAt", command.startsAt)
                .param("salesOpenAt", command.salesOpenAt)
                .param("salesCloseAt", command.salesCloseAt)
                .query(Long::class.java)
                .single()
        } catch (e: DuplicateKeyException) {
            // `performance_hall_slot_key`(`V18`)가 막은 것이다. 형식은 맞는데 그 홀의 그 시각이 이미 찼으므로 409 다(`D5` 상태 코드 표) —
            // `validation-failed` 는 Bean Validation 몫이고 그 계약이 `errors[{field, message}]` 라, 여기 쓰면 화면이 못 찾는 칸을 약속한다.
            throw TicketException(ErrorCode.PERFORMANCE_SLOT_TAKEN)
        }

        auditLog.record(
            AuditLog.Kind.OUTCOME,
            "performance.created",
            accountId,
            AuditLog.Target.of("performance", performanceId),
            mapOf("event_id" to eventId, "hall_id" to command.hallId),
        )
        return performanceId
    }

    private fun insertGrade(eventId: Long, grade: GradeCommand): Long =
        jdbc.sql(
            "insert into seat_grade (event_id, code, name, price) values (:event, :code, :code, :price) returning seat_grade_id",
        )
            .param("event", eventId)
            .param("code", grade.code)
            .param("price", grade.price)
            .query(Long::class.java)
            .single()

    private fun mapSection(eventId: Long, section: String, gradeId: Long) =
        try {
            jdbc.sql("insert into seat_grade_map (event_id, section, seat_grade_id) values (:event, :section, :grade)")
                .param("event", eventId).param("section", section).param("grade", gradeId).update()
        } catch (e: DuplicateKeyException) {
            // 구역 하나가 등급 둘을 가지면 그 구역의 가격이 안 정해진다(`V5` 기본키).
            throw TicketException(ErrorCode.VALIDATION_FAILED, "같은 구역에 등급을 두 번 붙였다: $section")
        }
}
