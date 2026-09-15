package com.projectticket.ticket.notification

import com.projectticket.ticket.outbox.EventType
import com.projectticket.ticket.outbox.OutboxRelay
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * 알림 표를 만지는 트랜잭션 전부. [record] 는 사건을 행으로 바꾸고(**보내지 않는다** — 바깥 호출은 트랜잭션 밖이라(`D4`) [NotificationSweeper] 가 따로 한다), 나머지는 그 스윕이 쓰는 고르기·표시다.
 *
 * **스윕과 빈이 갈린 이유는 자기 호출이다** — 같은 클래스 안에서 부르면 프록시를 안 지나 `` 이 통째로 무시된다(`stack.md`).
 *
 * **`REQUIRES_NEW` 다**(사용자 선택). 릴레이가 자기 트랜잭션 안에서 Spring 이벤트를 발행하므로, 이 경계가 없으면
 * 알림 쪽 실패가 릴레이 트랜잭션을 롤백시켜 `published_at` 이 안 찍히고 **같은 사건이 1초마다 영영 재발행**된다.
 * `D11` 이 「소비자 하나의 결함이 발행자를 멈추지 않는다」고 정한 자리고, 28 에서 Kafka 로 가면 이 경계가 프로세스 경계가 된다.
 *
 * 페이로드의 식별자로 **표를 다시 읽는다**(`D11`) — 사건은 「무슨 일이 났나」고 지금 상태는 표가 안다.
 */
@Component
class NotificationStore(private val jdbc: JdbcClient) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(envelope: OutboxRelay.Envelope) {
        when (envelope.type) {
            EventType.RESERVATION_RESERVED -> recordReserved(envelope)
            EventType.RESERVATION_CANCELLED -> recordCancelled(envelope)
            EventType.PERFORMANCE_CANCELLED -> recordPerformanceCancelled(envelope)
            // 종료는 사람에게 알릴 것이 없다 — 산 사람은 그대로 본다.
            EventType.PERFORMANCE_CLOSED -> Unit
        }
    }

    private fun recordReserved(envelope: OutboxRelay.Envelope) {
        val reservationId = envelope.aggregateId
        val performance = performanceOf(reservationId)
        val seats = jdbc.sql(
            """
            select s.section || ' ' || s.row_label || s.seat_number
              from reservation_seat rs
              join performance_seat ps on ps.performance_seat_id = rs.performance_seat_id
              join seat s on s.seat_id = ps.seat_id
             where rs.reservation_id = :id
             order by s.section, s.row_label, s.seat_number
            """,
        ).param("id", reservationId).query(String::class.java).list().filterNotNull()

        val tickets = jdbc.sql(
            """
            select t.ticket_number
              from ticket t join reservation_seat rs on rs.reservation_seat_id = t.reservation_seat_id
             where rs.reservation_id = :id
             order by t.ticket_number
            """,
        ).param("id", reservationId).query(String::class.java).list().filterNotNull()

        val (subject, body) = NotificationTemplates.reserved(
            performance,
            seats.joinToString(", "),
            (envelope.payload["total_amount"] as Number).toInt(),
            tickets,
        )
        insert(envelope, accountId(envelope), subject, body)
    }

    private fun recordCancelled(envelope: OutboxRelay.Envelope) {
        val performance = performanceOf(envelope.aggregateId)
        val (subject, body) = NotificationTemplates.cancelled(
            performance,
            daysBeforeOf(envelope),
            (envelope.payload["fee_amount"] as Number).toInt(),
            (envelope.payload["refund_amount"] as Number).toInt(),
        )
        insert(envelope, accountId(envelope), subject, body)
    }

    /** 일수는 사건에 없다(`D11` 카탈로그) — 취소 시점에 박제된 환불 행이 든다(`D6`) */
    private
    fun daysBeforeOf(envelope: OutboxRelay.Envelope): Int =
        jdbc.sql("select days_before from refund where refund_id = :id")
            .param("id", (envelope.payload["refund_id"] as Number).toLong())
            .query(Int::class.java)
            .single()

    /**
     * **수신자가 여럿인 유일한 사건**(`D11`). 사건은 회차 id 만 나르고 예매자는 여기서 표를 읽는다 —
     * 그래서 `(event_id, account_id)` 가 멱등의 키다.
     *
     * **계정마다 한 통이라 금액을 계정 단위로 합친다.** 한 계정이 이 회차에 예매를 둘 했어도 키가 계정이라 둘째 `insert` 는
     * `do nothing` 이다 — 예매마다 한 행을 내면 둘째 예매의 환불액이 소리 없이 사라진다.
     *
     * **관객이 먼저 취소한 예매도 `cancelled` 라 여기 섞인다**(24a). 그쪽은 수수료를 뗀 부분 환불인데 문구가 「전액」이라 틀린다 —
     * 예매에 「무엇이 물렀나」가 없어서 지금은 못 가른다.
     */
    private fun recordPerformanceCancelled(envelope: OutboxRelay.Envelope) {
        val performanceId = envelope.aggregateId
        val performance = performanceLineOf(performanceId)
        jdbc.sql(
            """
            select r.account_id, coalesce(sum(rf.refund_amount), 0)::int as refund_amount
              from reservation r
              left join payment pm on pm.reservation_id = r.reservation_id and pm.status = 'approved'
              left join refund rf on rf.payment_id = pm.payment_id
             where r.performance_id = :id and r.status = 'cancelled'
             group by r.account_id
             order by r.account_id
            """,
        ).param("id", performanceId).query(CancelledLine::class.java).list().filterNotNull()
            .forEach {
                val (subject, body) = NotificationTemplates.performanceCancelled(performance, it.refundAmount)
                insert(envelope, it.accountId, subject, body)
            }
    }

    private fun performanceLineOf(performanceId: Long): NotificationTemplates.PerformanceLine =
        jdbc.sql(
            "select e.title, p.starts_at from performance p join event e on e.event_id = p.event_id where p.performance_id = :id",
        ).param("id", performanceId).query(Line::class.java).single().let {
            NotificationTemplates.PerformanceLine(it.title, it.startsAt)
        }

    data class CancelledLine(val accountId: Long, val refundAmount: Int)

    private fun accountId(envelope: OutboxRelay.Envelope): Long = (envelope.payload["account_id"] as Number).toLong()

    private fun performanceOf(reservationId: Long): NotificationTemplates.PerformanceLine =
        jdbc.sql(
            """
            select e.title, p.starts_at
              from reservation r
              join performance p on p.performance_id = r.performance_id
              join event e on e.event_id = p.event_id
             where r.reservation_id = :id
            """,
        ).param("id", reservationId).query(Line::class.java).single().let {
            NotificationTemplates.PerformanceLine(it.title, it.startsAt)
        }

    /**
     * **두 번 온 사건은 0행으로 끝난다**(`on conflict do nothing`). 유일 위반을 예외로 받으면 트랜잭션이 어보트돼(`25P02`)
     * 뒤 문장이 못 돈다 — 멱등을 앱 검증이 아니라 제약으로 두는 방식이다(`D11`).
     */
    private fun insert(envelope: OutboxRelay.Envelope, accountId: Long, subject: String, body: String) {
        jdbc.sql(
            """
            insert into notification (event_id, event_type, account_id, subject, body)
            values (:eventId, :eventType, :account, :subject, :body)
            on conflict (event_id, account_id) do nothing
            """,
        )
            .param("eventId", envelope.eventId)
            .param("eventType", envelope.type.code)
            .param("account", accountId)
            .param("subject", subject)
            .param("body", body)
            .update()
    }

    /**
     * 보낼 것을 고른다. `for update skip locked` 로 **한 회에 같은 행을 둘이 안 집는다** — 다만 이 트랜잭션이 끝나면 락이 풀린다.
     * 발송과 표시가 다른 트랜잭션이라 그 사이에 남이 같은 행을 집으면 **같은 메일이 두 번 나갈 수 있다**([NotificationSweeper] 가 그 대가를 든다).
     */
    @Transactional
    fun takePending(batchSize: Int): List<Pending> =
        jdbc.sql(
            """
            select n.notification_id, n.subject, n.body, a.email
              from notification n join account a on a.account_id = n.account_id
             where n.status = 'pending'
             order by n.notification_id
             limit :batch
               for update of n skip locked
            """,
        ).param("batch", batchSize).query(Pending::class.java).list().filterNotNull()

    /** 조건부다 — 남이 먼저 표시했으면 0행이고 그 알림은 그쪽이 보낸 것이다 */
    @Transactional
    fun markSent(notificationId: Long) =
        jdbc.sql("update notification set status = :sent, sent_at = now() where notification_id = :id and status = 'pending'")
            .param("sent", NotificationStatus.SENT.code).param("id", notificationId).update()

    @Transactional
    fun markFailed(notificationId: Long, reason: String) =
        jdbc.sql("update notification set status = :failed, failure_reason = :reason where notification_id = :id and status = 'pending'")
            .param("failed", NotificationStatus.FAILED.code).param("reason", reason).param("id", notificationId).update()

    @Transactional
    fun markSkipped(notificationId: Long, reason: String) =
        jdbc.sql("update notification set status = :skipped, failure_reason = :reason where notification_id = :id and status = 'pending'")
            .param("skipped", NotificationStatus.SKIPPED.code).param("reason", reason).param("id", notificationId).update()

    /** @param email 탈퇴한 계정이면 null — 파기가 컬럼을 비운다 */
    data class Pending(val notificationId: Long, val subject: String, val body: String, val email: String?)

    data class Line(val title: String, val startsAt: OffsetDateTime)
}
