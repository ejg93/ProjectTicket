package com.projectticket.ticket.reservation

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.event.PerformanceSeatStatus
import com.projectticket.ticket.event.SeatVersions
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 선점 만료 스윕(`D3` 「자동 전이 셋」). `held_until` 이 지난 `held` 예매를 `expired` 로, 그 좌석을 `available` 로.
 *
 * **둘 다 조건부 UPDATE 라 멱등이다** — 두 번 돌아도 둘째는 0행이다. 결제가 시작된 예매는 `paying` 이라 첫 문장의 `where` 에 안 걸리고,
 * 그것이 스윕과 승인이 같은 행을 두고 다투지 않는 이유다(`D4` 「스윕과 확정의 경합」).
 *
 * **시계는 DB 다**(`D7`). 앱 시계로 재면 인스턴스 셋의 시계가 어긋날 때 한 대가 넣은 `held_until` 을 다른 대가 「아직」이라고 본다.
 *
 * 좌석은 만료된 **예매의 id** 로 고른다. `performance_seat.held_until < now()` 로 고르면 `paying` 예매의 좌석(여전히 `held`, 시각은 지남)까지 풀린다.
 */
@Component
class HoldSweeper(
    private val jdbc: JdbcClient,
    private val auditLog: AuditLog,
    private val seatVersions: SeatVersions,
) {

    private val log = LoggerFactory.getLogger(HoldSweeper::class.java)

    /**
     * 선점 만료와 결제 타임아웃을 한 회에 훑는다(`D7` 「자동 전이의 주기와 기준」 — 같은 스케줄러).
     * 타임아웃(`paying_until < now()`)은 결제 승인의 조건부 UPDATE(`where paying_until >= now()`)와 같은 행을 두고 다투지만
     * 둘 다 조건부라 한쪽만 이긴다 — 승인이 지면 `payment_late` 다(`D4`).
     *
     * @return 만료시킨 예매 수
     */
    @Scheduled(fixedDelayString = SWEEP_INTERVAL)
    @Transactional
    fun sweep(): Int {
        val expired = jdbc.sql(
            """
            update reservation
               set status = :expired, expired_at = now(), paying_until = null, cancelled_by = :by
             where status = :held and held_until < now()
                or status = :paying and paying_until < now()
            returning reservation_id
            """,
        )
            .param("expired", ReservationStatus.EXPIRED.code)
            .param("by", CancelledBy.EXPIRED.code)
            .param("held", ReservationStatus.HELD.code)
            .param("paying", ReservationStatus.PAYING.code)
            .query(Long::class.java)
            .list()
            .filterNotNull()

        if (expired.isEmpty()) {
            log.debug("선점 만료 스윕 — 대상 없음")
            return 0
        }

        val releasedSeats = jdbc.sql(
            """
            update performance_seat
               set status = :available, held_until = null, reservation_id = null
             where status = :held and reservation_id in (:reservations)
            returning performance_seat_id, performance_id
            """,
        )
            .param("available", PerformanceSeatStatus.AVAILABLE.code)
            .param("held", PerformanceSeatStatus.HELD.code)
            .param("reservations", expired)
            .query(SeatVersions.Row::class.java)
            .list()
            .filterNotNull()
        val released = releasedSeats.size
        // 좌석이 돌아온 것을 화면이 알아야 한다(`D20`). 커밋 뒤에 판이 오른다.
        seatVersions.publishAfterCommit(releasedSeats, PerformanceSeatStatus.AVAILABLE)

        // 전이마다 감사 사건 하나(`D3` 「상태 이력」). 행위자가 없다 — 시간이 옮긴 것이다.
        expired.forEach { reservationId ->
            auditLog.record(AuditLog.Kind.OUTCOME, "reservation.expired", null, AuditLog.Target.of("reservation", reservationId))
        }
        log.info("선점 만료 스윕 예매={}건 좌석={}석", expired.size, released)
        return expired.size
    }

    companion object {
        /** ADR 0003 — 30초. 「5분 선점이 5분 30초까지 남을 수 있다」는 뜻이고 화면의 카운트다운이 덮는다(`D7`) */
        const val SWEEP_INTERVAL = "PT30S"
    }
}
