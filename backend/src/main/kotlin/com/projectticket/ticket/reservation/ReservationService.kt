package com.projectticket.ticket.reservation

import com.projectticket.ticket.idempotency.IdempotencyService
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.sql.SQLException

/**
 * 선점의 입구. 멱등키로 감싸고 교착이면 한 번 다시 한다.
 *
 * 트랜잭션은 [IdempotencyService.run] 이 연다 — 키 선점·좌석 선점·응답 저장이 **한 트랜잭션**이어야 반쪽 상태가 없다(`D4` 「멱등키」).
 * 그래서 이 클래스에는 `@Transactional` 이 없다. 재시도가 트랜잭션 **밖**에 있어야 교착으로 어보트된 트랜잭션을 버리고 새로 열 수 있다.
 */
@Service
class ReservationService(
    private val idempotency: IdempotencyService,
    private val seatHold: SeatHoldService,
    private val reservationQuery: ReservationQuery,
) {

    fun hold(accountId: Long, idempotencyKey: String, command: SeatHoldService.Command): ReservationQuery.Reservation =
        retryOnceOnDeadlock {
            idempotency.run(accountId, idempotencyKey, command, ReservationQuery.Reservation::class.java) {
                reservationQuery.get(seatHold.hold(accountId, command), accountId)
            }
        }

    /**
     * `40P01`(교착)·`40001`(직렬화 실패)만 1회(`D4` 「재시도」). 0행은 남이 이긴 확정 답이라 예외 종류가 다르고 여기 안 걸린다.
     *
     * **SQLSTATE 를 직접 본다.** Spring 은 Postgres 의 `40P01` 을 `DeadlockLoserDataAccessException` 이 아니라
     * 상위 타입으로 번역해서 예외 이름으로 잡으면 놓친다(`stack.md`).
     */
    private fun <T> retryOnceOnDeadlock(work: () -> T): T =
        try {
            work()
        } catch (e: DataAccessException) {
            if (sqlStateOf(e) in RETRYABLE_SQL_STATES) work() else throw e
        }

    private fun sqlStateOf(e: Throwable): String? =
        generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    companion object {
        private val RETRYABLE_SQL_STATES = setOf("40P01", "40001")
    }
}
