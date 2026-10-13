package com.projectticket.ticket.event

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.jdbc.core.simple.JdbcClient
import com.projectticket.ticket.observability.TicketMetrics
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * 좌석 현황 읽기(`D20`). 트랜잭션을 안 연다 — 읽기 쪽이다(`D15`).
 *
 * **버전과 스냅샷은 Redis 다**(`10a`, [SeatVersions]). DB 는 스냅샷을 만들 때만 읽는다 — 같은 버전의 두 번째 요청부터는
 * Redis 가 답한다. Redis 가 비어 있으면 [SeatVersions] 가 `performance_seat.updated_at` 의 최댓값으로 씨를 뿌린다.
 *
 * [version] 과 [seatMap] 사이에 좌석이 바뀔 수 있다. 그러면 응답 본문이 `ETag` 보다 새롭고, 다음 폴링이 한 번 더 200 을 받는다 —
 * 화면이 틀리는 것이 아니라 한 번 더 받는 것이다. `D20` 의 Redis 경로도 같은 모양이라 여기서 안 막는다.
 */
@Component
class SeatQuery(
    private val jdbc: JdbcClient,
    private val versions: SeatVersions,
    private val json: ObjectMapper,
    private val metrics: TicketMetrics,
) {

    /** 지금 이 대에서 만드는 중인 스냅샷 — `"$performanceId:$version"` 마다 하나(`35j`) */
    private val building = ConcurrentHashMap<String, CompletableFuture<SeatMap>>()

    private val log = LoggerFactory.getLogger(SeatQuery::class.java)

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
        if (status == PerformanceStatus.DRAFT.code) throw TicketException(ErrorCode.PERFORMANCE_NOT_FOUND)

        return versions.current(performanceId)
    }

    /**
     * `since` 뒤로 바뀐 좌석만(`D20` 「델타」). 로그가 잘려 그 사이를 못 보여 주면 410 이다 —
     * 화면은 그 코드를 보고 전체를 다시 받는다. 조용히 빈 목록을 주면 **화면이 영영 틀린 그림을 든다.**
     */
    fun changes(performanceId: Long, since: Long): SeatChanges {
        version(performanceId)
        val changes = versions.changesSince(performanceId, since)
        if (changes.truncated) {
            throw TicketException(
                ErrorCode.SEAT_CHANGES_EXPIRED,
                "변경 로그 밖이다: since=$since",
                mapOf("version" to changes.version),
            )
        }
        return SeatChanges(changes.version, changes.changes)
    }

    /**
     * 버전이 같으면 Redis 가 답한다(`D20`). DB 를 읽는 것은 **버전이 바뀐 직후 첫 요청** 하나뿐이고,
     * 나머지 폴링은 그 그림을 나눠 쓴다.
     *
     * **같은 대 안에서는 판마다 하나만 만든다**(single-flight, `35j`, 사용자 결정 2026-10-13). 판이 바뀐 직후 첫 생성이 끝나기 전에 온
     * 요청이 각자 만들어서, 1,000 rps 에서 판 하나에 약 20번 만들었다(`load-3` 「35d」). 늦게 온 요청은 만드는 쪽의 결과를 기다린다.
     * **대 사이는 안 잠근다** — Redis 락이 스냅샷보다 비싸다(`D20`). 그래서 같은 판의 생성 상한은 대수다.
     * 기다림은 [BUILD_WAIT] 까지 — 만드는 쪽이 늦거나 죽으면 직접 만든다. 화면이 멈추는 것보다 한 번 더 만드는 것이 싸다.
     */
    fun seatMap(performanceId: Long): SeatMap {
        val version = version(performanceId)
        versions.cachedSnapshot(performanceId, version)?.let { return json.readValue(it, SeatMap::class.java) }

        val key = "$performanceId:$version"
        val mine = CompletableFuture<SeatMap>()
        val running = building.putIfAbsent(key, mine)
        if (running != null) {
            return try {
                running.get(BUILD_WAIT.toMillis(), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // 드문 일이라 알아야 한다 — 만드는 쪽이 [BUILD_WAIT] 를 넘겼다(DB 가 느리다)
                log.warn("스냅샷 기다림이 끝나 직접 만든다 performance_id={} version={}", performanceId, version, e)
                buildAndCache(performanceId, version)
            } catch (e: ExecutionException) {
                log.warn("만드는 쪽이 실패해 직접 만든다 performance_id={} version={}", performanceId, version, e.cause ?: e)
                buildAndCache(performanceId, version)
            }
        }
        return try {
            buildAndCache(performanceId, version).also { mine.complete(it) }
        } catch (e: RuntimeException) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            building.remove(key, mine)
        }
    }

    private fun buildAndCache(performanceId: Long, version: Long): SeatMap =
        buildSeatMap(performanceId, version).also { versions.cacheSnapshot(performanceId, version, json.writeValueAsString(it)) }

    private fun buildSeatMap(performanceId: Long, version: Long): SeatMap {
        // 이 수가 폴링 수에 비례하면 캐시가 안 먹는 것이다(`D10`).
        metrics.snapshotBuilt()
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
    data class SeatChanges(val version: Long, val changes: List<SeatVersions.Change>)
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
        /** 같은 판을 만드는 쪽을 기다리는 상한(`35j`) */
        val BUILD_WAIT: Duration = Duration.ofSeconds(2)

        private val SECTION_CODE = Regex("^F([0-9]+)-([A-Z])$")

        /**
         * 구역 코드 → 표시 이름. `F1-A` → `1층 A구역`. 형식은 `seat_section_format_check` 가 강제하므로 안 맞으면 제약이 빠진 것이다 —
         * 코드를 그대로 돌려주면 그 사실이 화면에서 안 보인다.
         */
        fun sectionName(code: String): String {
            val (floor, letter) = SECTION_CODE.matchEntire(code)?.destructured
                ?: error("구역 코드 형식이 아니다: $code")
            return "${floor}층 ${letter}구역"
        }
    }
}
