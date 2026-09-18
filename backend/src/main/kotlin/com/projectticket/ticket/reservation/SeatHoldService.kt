package com.projectticket.ticket.reservation

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.event.PerformanceSeatStatus
import com.projectticket.ticket.event.SeatVersions
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 좌석 선점의 트랜잭션. `D4` 「선점의 문장 순서」 그대로다:
 *
 * ```
 * 1. reservation 행 삽입 (held, held_until)      → reservation_id
 * 2. 좌석 id 오름차순 select … for update         → 교착 방지
 * 3. 조건부 update (available 이고 회차가 open)
 * 4. 갱신 행 수 ≠ 요청 좌석 수 → 예외 → 전부 롤백
 * 5. reservation_seat 기록
 * ```
 *
 * 1 이 먼저인 이유는 `performance_seat_held_fields_check` 가 `held` 행에 `reservation_id` 를 요구해서다(`V6`).
 * 4 에서 던지는 예외가 1 의 행을 지운다 — **진 쪽의 예매가 안 남는 것은 이 셋이 한 트랜잭션이기 때문**이다.
 *
 * 「승자 하나」는 3 의 `where status = 'available'` 이 Read Committed 의 WHERE 재평가로 보장한다(`D4` §13.2.1).
 * `select` 로 먼저 확인하지 않는다 — 그 사이에 남이 끼어든다.
 *
 * 관문(`X-Admission-Token`)은 여기가 아니라 23 이 컨트롤러 앞에 세운다. 이 서비스는 「토큰이 있는 사람」이 이미 걸러졌다고 본다.
 */
@Service
class SeatHoldService(
    private val jdbc: JdbcClient,
    private val auditLog: AuditLog,
    private val seatVersions: SeatVersions,
) {

    /** 멱등키의 본문 해시가 이것으로 만들어진다 — 같은 회차·같은 좌석 순서면 같은 요청이다 */
    data class Command(val performanceId: Long, val seatIds: List<Long>)

    /** @return 만든 예매 id. 실패는 전부 [TicketException] 이고 그때 이 트랜잭션은 통째로 롤백된다 */
    @Transactional
    fun hold(accountId: Long, command: Command): Long {
        val seatIds = command.seatIds.distinct()
        if (seatIds.size > MAX_SEATS_PER_HOLD) {
            throw TicketException(
                ErrorCode.OVER_LIMIT,
                "한 번에 ${MAX_SEATS_PER_HOLD}석까지다: ${seatIds.size}석",
                mapOf("limit" to MAX_SEATS_PER_HOLD, "requested" to seatIds.size),
            )
        }

        // 회차 상태를 먼저 읽는 것은 **친절한 오류를 주기 위해서**다. 강제는 아래 조건부 UPDATE 의 `exists` 가 한다 — 그 사이에 닫혀도 0행이다.
        val performanceStatus = performanceStatusOf(command.performanceId)
        if (performanceStatus != "open") {
            throw TicketException(
                ErrorCode.PERFORMANCE_NOT_OPEN,
                "판매 중인 회차가 아니다: performance_id=${command.performanceId}, status=$performanceStatus",
                mapOf("performance_status" to performanceStatus),
            )
        }

        // 회차당 매수(ADR 0003). 살아있는 예매(held·paying·reserved)의 좌석 수에 이번 요청을 더해 본다.
        // 앱 검증인데 경합에 안전한 이유는 `reservation_live_hold_idx`(`V8`) — 같은 계정의 둘째 선점은 첫째가 끝날 때까지 insert 에서 기다린다.
        val alreadyHeld = seatsHeldBy(accountId, command.performanceId)
        if (alreadyHeld + seatIds.size > MAX_SEATS_PER_PERFORMANCE) {
            throw TicketException(
                ErrorCode.OVER_LIMIT,
                "회차당 ${MAX_SEATS_PER_PERFORMANCE}매까지다: 이미 ${alreadyHeld}매, 요청 ${seatIds.size}매",
                mapOf("limit" to MAX_SEATS_PER_PERFORMANCE, "requested" to alreadyHeld + seatIds.size),
            )
        }

        val reservationId = insertReservation(accountId, command.performanceId, seatIds)
            ?: throw TicketException(
                ErrorCode.DUPLICATE_HOLD,
                "이 회차에 살아있는 선점이 이미 있다",
                mapOf("reservation_id" to liveReservationOf(accountId, command.performanceId)),
            )

        val locked = lockInIdOrder(command.performanceId, seatIds)
        if (locked.size != seatIds.size) {
            val missing = seatIds - locked.toSet()
            throw TicketException(
                ErrorCode.SEAT_NOT_IN_PERFORMANCE,
                "이 회차의 좌석이 아니다: ${missing.joinToString()}",
                mapOf("seat_ids" to missing),
            )
        }

        val heldSeats = holdAvailable(command.performanceId, seatIds, reservationId)
        val held = heldSeats.map { it.performanceSeatId }
        if (held.size != seatIds.size) {
            // 0행의 원인이 「좌석이 잡혔다」인지 「그 사이에 회차가 닫혔다」인지는 잠근 상태에서 다시 읽으면 갈린다.
            val statusNow = performanceStatusOf(command.performanceId)
            if (statusNow != "open") {
                throw TicketException(ErrorCode.PERFORMANCE_NOT_OPEN, "회차가 닫혔다: status=$statusNow", mapOf("performance_status" to statusNow))
            }
            val taken = seatIds - held.toSet()
            throw TicketException(
                ErrorCode.SEAT_TAKEN,
                "${seatIds.size}석 중 ${taken.size}석이 이미 잡혔다",
                mapOf("taken_seat_ids" to taken),
            )
        }

        recordSeats(reservationId, seatIds)

        // 잡힌 좌석을 화면이 알아야 한다(`D20`). **커밋 뒤**라 롤백된 선점은 화면에 안 보인다.
        seatVersions.publishAfterCommit(heldSeats, PerformanceSeatStatus.HELD)

        auditLog.record(
            AuditLog.Kind.OUTCOME,
            "reservation.held",
            accountId,
            AuditLog.Target.of("reservation", reservationId),
            mapOf("performance_id" to command.performanceId, "seat_count" to seatIds.size),
        )
        return reservationId
    }

    private fun performanceStatusOf(performanceId: Long): String =
        jdbc.sql("select status from performance where performance_id = :id")
            .param("id", performanceId)
            .query(String::class.java)
            .optional()
            .orElseThrow { TicketException(ErrorCode.PERFORMANCE_NOT_FOUND) }

    /** 이 계정이 이 회차에 살아있는 예매로 쥔 좌석 수. `expired`·`cancelled` 는 안 센다 — 좌석이 돌아갔다 */
    private fun seatsHeldBy(accountId: Long, performanceId: Long): Int =
        jdbc.sql(
            """
            select count(*)
              from reservation_seat rs
              join reservation r on r.reservation_id = rs.reservation_id
             where r.account_id = :account and r.performance_id = :performance
               and r.status in (:live)
            """,
        )
            .param("account", accountId)
            .param("performance", performanceId)
            .param("live", LIVE_STATUSES)
            .query(Int::class.java)
            .single()

    private fun liveReservationOf(accountId: Long, performanceId: Long): Long? =
        jdbc.sql(
            "select reservation_id from reservation where account_id = :account and performance_id = :performance and status in (:live)",
        )
            .param("account", accountId)
            .param("performance", performanceId)
            .param("live", listOf(ReservationStatus.HELD.code, ReservationStatus.PAYING.code))
            .query(Long::class.java)
            .optional()
            .orElse(null)

    /**
     * 합계는 요청한 좌석의 박제 가격 합이다. 다른 회차의 id 가 섞이면 합이 작게 나오지만 그 요청은 아래 잠금 단계에서 죽고,
     * 그래도 어긋난 채 커밋되려 하면 `reservation_total_check` 가 막는다.
     *
     * @return 살아있는 선점이 이미 있으면 null. `on conflict … do nothing` 이라 트랜잭션이 어보트되지 않고, 그래서 뒤이어 그 예매 id 를 읽을 수 있다 —
     *   예외로 받으면 `25P02` 라 다음 문장이 못 돈다.
     *
     * `on conflict` 의 `where` 에 상태를 **리터럴로** 쓴다(`D14` 「SQL」의 예외). 바인딩하면 플래너가 부분 인덱스의 조건과 같다는 것을 못 증명해서
     * 「matching constraint 가 없다」로 죽는다. 인덱스 조건(`V8`)과 글자까지 같아야 한다.
     */
    private fun insertReservation(accountId: Long, performanceId: Long, seatIds: List<Long>): Long? =
        jdbc.sql(
            """
            insert into reservation (account_id, performance_id, total_amount, held_until)
            select :account, :performance, coalesce(sum(price), 0), now() + make_interval(mins => :minutes)
              from performance_seat
             where performance_id = :performance and performance_seat_id in (:seatIds)
            on conflict (account_id, performance_id) where status in ('held', 'paying') do nothing
            returning reservation_id
            """,
        )
            .param("account", accountId)
            .param("performance", performanceId)
            .param("seatIds", seatIds)
            .param("minutes", HOLD_MINUTES)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    /**
     * **id 오름차순으로 먼저 잠근다.** 한 문장 `update … where id in (…)` 은 잠그는 순서를 보장하지 않아서,
     * 두 요청이 겹치는 좌석을 다른 순서로 잠그면 `40P01` 이다. 순서를 고정하면 순환이 안 생긴다(`D4`).
     */
    private fun lockInIdOrder(performanceId: Long, seatIds: List<Long>): List<Long> =
        jdbc.sql(
            """
            select performance_seat_id
              from performance_seat
             where performance_id = :performance and performance_seat_id in (:seatIds)
             order by performance_seat_id
               for update
            """,
        )
            .param("performance", performanceId)
            .param("seatIds", seatIds)
            .query(Long::class.java)
            .list()
            .filterNotNull()

    /** 조건부 UPDATE. 갱신된 행이 곧 잡은 좌석이다 — 갱신 행 수가 판정이고 0행은 「남이 이겼다」는 확정 답이다 */
    private fun holdAvailable(performanceId: Long, seatIds: List<Long>, reservationId: Long): List<SeatVersions.Row> =
        jdbc.sql(
            """
            update performance_seat ps
               set status = :held,
                   held_until = r.held_until,
                   reservation_id = r.reservation_id
              from reservation r
             where r.reservation_id = :reservation
               and ps.performance_id = :performance
               and ps.performance_seat_id in (:seatIds)
               and ps.status = :available
               and exists (select 1 from performance p where p.performance_id = :performance and p.status = 'open')
            returning ps.performance_seat_id, ps.performance_id
            """,
        )
            .param("held", PerformanceSeatStatus.HELD.code)
            .param("available", PerformanceSeatStatus.AVAILABLE.code)
            .param("reservation", reservationId)
            .param("performance", performanceId)
            .param("seatIds", seatIds)
            .query(SeatVersions.Row::class.java)
            .list()
            .filterNotNull()

    private fun recordSeats(reservationId: Long, seatIds: List<Long>) =
        jdbc.sql(
            """
            insert into reservation_seat (reservation_id, performance_seat_id, price)
            select :reservation, performance_seat_id, price
              from performance_seat
             where performance_seat_id in (:seatIds)
            """,
        )
            .param("reservation", reservationId)
            .param("seatIds", seatIds)
            .update()

    companion object {
        /** ADR 0003 — 한 번에 고르는 좌석은 4석 */
        const val MAX_SEATS_PER_HOLD = 4

        /** ADR 0003 — 회차당 계정당 4매. 살아있는 예매(held·paying·reserved)의 좌석을 센다 */
        const val MAX_SEATS_PER_PERFORMANCE = 4

        private val LIVE_STATUSES = listOf(ReservationStatus.HELD.code, ReservationStatus.PAYING.code, ReservationStatus.RESERVED.code)

        /** ADR 0003 — 선점 5분. 만료 시각은 DB 가 계산한다(`D7`) */
        const val HOLD_MINUTES = 5
    }
}
