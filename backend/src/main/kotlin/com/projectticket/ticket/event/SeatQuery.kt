package com.projectticket.ticket.event

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * 좌석 현황 읽기(`D20`). 트랜잭션을 안 연다 — 읽기 쪽이다(`D15`).
 *
 * **버전은 아직 DB 에서 만든다.** `D20` 의 본길은 Redis `INCR` 인데 Redis 는 뒤 청크가 들인다. 그때까지는 `D20` 이 Redis 가
 * 비었을 때 쓰라고 정한 대체값 — `performance_seat.updated_at` 의 최댓값 — 을 버전으로 쓴다. 마이크로초 단위 epoch 라
 * 좌석이 하나라도 바뀌면 값이 는다(`set_updated_at` 트리거). Redis 가 들어오면 [version] 만 바뀌고 계약은 그대로다.
 *
 * [version] 과 [seatMap] 사이에 좌석이 바뀔 수 있다. 그러면 응답 본문이 `ETag` 보다 새롭고, 다음 폴링이 한 번 더 200 을 받는다 —
 * 화면이 틀리는 것이 아니라 한 번 더 받는 것이다. `D20` 의 Redis 경로도 같은 모양이라 여기서 안 막는다.
 */
@Component
class SeatQuery(private val jdbc: JdbcClient) {

    /**
     * 회차의 현재 버전. **없거나 `draft` 면 404** — 아직 공개되지 않은 회차는 밖에서 보면 없는 것이다(`D5` 「403 이냐 404 냐」).
     * 재검증(304)이 이것만 읽고 끝나도록 좌석과 따로 뒀다.
     */
    fun version(performanceId: Long): Long {
        val status = jdbc.sql("select status from performance where performance_id = :id")
            .param("id", performanceId)
            .query(String::class.java)
            .optional()
            .orElseThrow { TicketException(ErrorCode.PERFORMANCE_NOT_FOUND) }
        if (status == "draft") throw TicketException(ErrorCode.PERFORMANCE_NOT_FOUND)

        return jdbc.sql(
            """
            select coalesce((extract(epoch from max(updated_at)) * 1000000)::bigint, 0)
              from performance_seat
             where performance_id = :id
            """,
        ).param("id", performanceId).query(Long::class.java).single()
    }

    fun seatMap(performanceId: Long): SeatMap {
        val version = version(performanceId)
        val grades = jdbc.sql(
            """
            select distinct g.code, g.name, ps.price
              from performance_seat ps
              join seat_grade g on g.seat_grade_id = ps.seat_grade_id
             where ps.performance_id = :id
             order by ps.price desc, g.code
            """,
        ).param("id", performanceId).query(Grade::class.java).list().filterNotNull()

        // 등급·가격은 `seat_grade` 가 아니라 `performance_seat` 의 박제값이다 — 공연이 값을 고쳐도 열린 회차는 안 바뀐다(`V6`).
        val seats = jdbc.sql(
            """
            select ps.performance_seat_id as id, s.section, s.row_label, s.seat_number, ps.status, g.code as grade
              from performance_seat ps
              join seat s on s.seat_id = ps.seat_id
              join seat_grade g on g.seat_grade_id = ps.seat_grade_id
             where ps.performance_id = :id
             order by s.section, s.row_label, s.seat_number
            """,
        ).param("id", performanceId).query(SeatRow::class.java).list().filterNotNull()

        val sections = seats.groupBy { it.section }.map { (section, inSection) ->
            Section(
                code = section,
                name = sectionName(section),
                grade = inSection.first().grade,
                rows = inSection.groupBy { it.rowLabel }.map { (label, inRow) ->
                    Row(label, inRow.map { Seat(it.id, it.seatNumber, PerformanceSeatStatus.of(it.status).letter) })
                },
            )
        }
        return SeatMap(version, grades, sections)
    }

    data class SeatMap(val version: Long, val grades: List<Grade>, val sections: List<Section>)
    data class Grade(val code: String, val name: String, val price: Int)
    data class Section(val code: String, val name: String, val grade: String, val rows: List<Row>)
    data class Row(val label: String, val seats: List<Seat>)

    /** 키가 한 글자인 이유는 크기다(`D20`). `n` 좌석 번호, `s` 상태 한 글자 */
    data class Seat(val id: Long, val n: Int, val s: String)

    data class SeatRow(
        val id: Long,
        val section: String,
        val rowLabel: String,
        val seatNumber: Int,
        val status: String,
        val grade: String,
    )

    companion object {
        private val SECTION_CODE = Regex("^F([0-9]+)-([A-Z])$")

        /**
         * 구역 코드 → 표시 이름. `F1-A` → `1층 A구역`. 형식은 `seat_section_format_check` 가 강제하므로 안 맞으면 제약이 빠진 것이다 —
         * 코드를 그대로 돌려주면 그 사실이 화면에서 안 보인다.
         */
        fun sectionName(code: String): String {
            val (floor, letter) = SECTION_CODE.matchEntire(code)?.destructured
                ?: throw IllegalStateException("구역 코드 형식이 아니다: $code")
            return "${floor}층 ${letter}구역"
        }
    }
}
