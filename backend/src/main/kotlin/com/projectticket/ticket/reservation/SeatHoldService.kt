package com.projectticket.ticket.reservation

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import com.projectticket.ticket.event.PerformanceSeatStatus
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
class SeatHoldService(private val jdbc: JdbcClient, private val auditLog: AuditLog) {

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

        val reservationId = insertReservation(accountId, command.performanceId, seatIds)

        val locked = lockInIdOrder(command.performanceId, seatIds)
        if (locked.size != seatIds.size) {
            val missing = seatIds - locked.toSet()
            throw TicketException(
                ErrorCode.SEAT_NOT_IN_PERFORMANCE,
                "이 회차의 좌석이 아니다: ${missing.joinToString()}",
                mapOf("seat_ids" to missing),
            )
        }

        val held = holdAvailable(command.performanceId, seatIds, reservationId)
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

    /**
     * 합계는 요청한 좌석의 박제 가격 합이다. 다른 회차의 id 가 섞이면 합이 작게 나오지만 그 요청은 아래 잠금 단계에서 죽고,
     * 그래도 어긋난 채 커밋되려 하면 `reservation_total_check` 가 막는다.
     */
    private fun insertReservation(accountId: Long, performanceId: Long, seatIds: List<Long>): Long =
        jdbc.sql(
            """
            insert into reservation (account_id, performance_id, total_amount, held_until)
            select :account, :performance, coalesce(sum(price), 0), now() + make_interval(mins => :minutes)
              from performance_seat
             where performance_id = :performance and performance_seat_id in (:seatIds)
            returning reservation_id
            """,
        )
            .param("account", accountId)
            .param("performance", performanceId)
            .param("seatIds", seatIds)
            .param("minutes", HOLD_MINUTES)
            .query(Long::class.java)
            .single()

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
    private fun holdAvailable(performanceId: Long, seatIds: List<Long>, reservationId: Long): List<Long> =
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
            returning ps.performance_seat_id
            """,
        )
            .param("held", PerformanceSeatStatus.HELD.code)
            .param("available", PerformanceSeatStatus.AVAILABLE.code)
            .param("reservation", reservationId)
            .param("performance", performanceId)
            .param("seatIds", seatIds)
            .query(Long::class.java)
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
        /** ADR 0003 — 한 번에 고르는 좌석은 4석. 회차당 4매 상한은 15 가 따로 건다 */
        const val MAX_SEATS_PER_HOLD = 4

        /** ADR 0003 — 선점 5분. 만료 시각은 DB 가 계산한다(`D7`) */
        const val HOLD_MINUTES = 5
    }
}
