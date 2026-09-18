package com.projectticket.ticket.notification

import tools.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.outbox.AggregateType
import com.projectticket.ticket.outbox.EventType
import com.projectticket.ticket.outbox.OutboxRelay
import com.projectticket.ticket.outbox.PendingEvents
import com.projectticket.ticket.payment.MockPaymentGateway
import com.projectticket.ticket.payment.PaymentTransitionService
import com.projectticket.ticket.payment.RefundTransitionService
import com.projectticket.ticket.reservation.SeatHoldService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 첫 소비자가 `D11` 대로 도는가 — **같은 사건이 두 번 와도 알림은 하나**, 본문은 표에서 읽어 채우고, 보낼 때 주소를 읽는다.
 *
 * **커밋 레인이다.** 소비자가 `REQUIRES_NEW` 로 도는데, 그 새 트랜잭션은 롤백 레인 테스트의 **미커밋 데이터를 못 본다** —
 * 본문을 채우려고 예매·좌석·티켓을 읽는 순간 0행이라 알림이 통째로 안 생긴다(`stack.md`).
 *
 * 스케줄러는 테스트에서 꺼져 있다(`SchedulingConfig`) — 릴레이·발송 회차를 손으로 부른다.
 */
class NotificationIdempotencyTest : ConcurrencyTestBase() {
    @Autowired lateinit var jdbcForEvents: JdbcClient
    @Autowired lateinit var jsonForEvents: ObjectMapper
    @Autowired lateinit var sweeper: NotificationSweeper
    @Autowired lateinit var store: NotificationStore
    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var payments: PaymentTransitionService
    @Autowired lateinit var refunds: RefundTransitionService
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private var accountId: Long = 0
    private var eventId: Long = 0
    private var hallId: Long = 0

    @BeforeEach
    fun setUp() {
        // **남이 커밋해 둔 알림을 치운다.** 28 뒤로 커밋 레인 테스트의 Kafka 소비자가 진짜 행을 남기고,
        // 컨테이너를 재사용하면 지난 실행 것까지 쌓인다 — 스윕은 전체를 훑으므로 그것들이 이 테스트의 수를 흔든다.
        // 이 트랜잭션 안의 삭제라 끝나면 되돌아간다(`stack.md` — 롤백 레인).
        jdbc.sql("delete from notification").update()

        fixture = EventFixture(jdbc)
        accountId = fixture.account("${PREFIX}mailbox@test.local")
        eventId = fixture.event(fixture.organizer("${PREFIX}org"), "겨울 콘서트")
        hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
    }

    @Test
    fun confirmation_becomes_one_mail_with_seats_and_tickets() {
        val reservationId = reserved(startsInDays = 8)

        deliver()

        val mail = mailOf(reservationId, "reservation.reserved")
        assertThat(mail.subject).isEqualTo("[예매 확정] 겨울 콘서트")
        assertThat(mail.status).isEqualTo("pending")
        // 사건은 식별자만 나른다 — 좌석·티켓 번호는 소비자가 표에서 읽는다(`D11`).
        assertThat(mail.body).contains("F1-A A1, F1-A A2").contains("308,000원").containsPattern("""\d{8}-[2-9A-HJ-NP-Z]{6}""")
        // 사용자가 읽는 글이라 존댓말이다(`D17`). 이름은 안 부른다 — 본문이 이력이라 탈퇴 뒤에도 남는다.
        assertThat(mail.body).contains("확정되었습니다").doesNotContain("mailbox@test.local")
    }

    @Test
    fun the_same_event_delivered_twice_makes_one_mail() {
        val reservationId = reserved(startsInDays = 8)
        val envelope = envelopeOf(reservationId, EventType.RESERVATION_RESERVED)

        store.record(envelope)
        store.record(envelope)

        // 멱등을 앱 검증이 아니라 제약으로 뒀다 — 두 번째는 `on conflict do nothing` 의 0행이다(`D11`).
        assertThat(mailCount(reservationId)).isOne()
    }

    @Test
    fun cancellation_mail_carries_the_refund_numbers() {
        val reservationId = reserved(startsInDays = 8)
        refunds.request(accountId, reservationId)

        deliver()

        val mail = mailOf(reservationId, "reservation.cancelled")
        assertThat(mail.subject).isEqualTo("[예매 취소] 겨울 콘서트")
        // 일수는 사건에 없다 — 취소 시점에 박제된 환불 행이 든다(`D6`).
        assertThat(mail.body).contains("관람일 8일 전").contains("30,800원").contains("277,200원")
    }

    @Test
    fun sweeping_sends_pending_mail_and_marks_it() {
        val reservationId = reserved(startsInDays = 8)
        deliver()

        assertThat(sweeper.sweep()).isEqualTo(1)

        assertThat(mailOf(reservationId, "reservation.reserved").status).isEqualTo("sent")
        // 보낸 것은 부분 인덱스에서 빠진다. 두 번째 회가 0 이 아니면 같은 메일이 계속 나간다.
        assertThat(sweeper.sweep()).isZero()
    }

    @Test
    fun a_bouncing_address_is_recorded_as_failed() {
        val bouncing = fixture.account("${PREFIX}nowhere@bounce.invalid")
        insertPending(bouncing)

        assertThat(sweeper.sweep()).isZero()

        val row = jdbc.sql("select status, failure_reason from notification where account_id = :id")
            .param("id", bouncing).query { rs, _ -> rs.getString("status") to rs.getString("failure_reason") }.single()
        assertThat(row).isEqualTo("failed" to "unknown_recipient")
    }

    @Test
    fun a_withdrawn_account_is_skipped_not_failed() {
        val leaving = fixture.account("${PREFIX}leaving@test.local")
        insertPending(leaving)
        // 파기는 컬럼을 비운다(`V2`). 다시 해도 같으니 재시도가 아니다.
        jdbc.sql("update account set email = null, password_hash = null, display_name = null, deleted_at = now() where account_id = :id")
            .param("id", leaving).update()

        sweeper.sweep()

        val row = jdbc.sql("select status, failure_reason from notification where account_id = :id")
            .param("id", leaving).query { rs, _ -> rs.getString("status") to rs.getString("failure_reason") }.single()
        assertThat(row).isEqualTo("skipped" to "withdrawn_account")
    }

    @Test
    fun a_sent_mail_cannot_be_rewritten() {
        val reservationId = reserved(startsInDays = 8)
        deliver()

        // 본문은 이력이다. 나중에 문구를 고치면 「그때 무엇을 보냈나」가 거짓이 된다.
        assertThatThrownBy {
            jdbc.sql("update notification set body = '다른 내용' where account_id = :id").param("id", accountId).update()
        }.hasStackTraceContaining("보낸 알림의 내용은 고칠 수 없다")
        assertThat(mailCount(reservationId)).isOne()
    }

    private fun reserved(startsInDays: Long): Long {
        val performanceId = fixture.performance(eventId, hallId, startsInDays).also { openService.open(it, actorAccountId = null) }
        val reservationId = seatHold.hold(accountId, SeatHoldService.Command(performanceId, fixture.performanceSeatIds(performanceId)))
        val paying = payments.startPaying(accountId, reservationId)
        payments.settle(accountId, paying, MockPaymentGateway.Result("M-${System.nanoTime()}", "4242", null))
        return reservationId
    }

    /** 아웃박스에 쌓인 그 사건을 봉투로. 릴레이를 안 거치고 소비자만 두 번 부를 때 쓴다 */
    private fun envelopeOf(reservationId: Long, type: EventType): OutboxRelay.Envelope {
        val row = jdbc.sql(
            "select event_id, occurred_at, payload::text as payload from outbox where type = :type and aggregate_id = :id",
        ).param("type", type.code).param("id", reservationId)
            .query { rs, _ ->
                Triple(rs.getObject("event_id", UUID::class.java), rs.getObject("occurred_at", OffsetDateTime::class.java), rs.getString("payload"))
            }.single()

        @Suppress("UNCHECKED_CAST")
        val payload = tools.jackson.databind.ObjectMapper().readValue(row.third, Map::class.java) as Map<String, Any?>
        return OutboxRelay.Envelope(row.first, type, 1, row.second, AggregateType.RESERVATION, reservationId, payload)
    }

    private fun insertPending(account: Long) =
        jdbc.sql(
            """
            insert into notification (event_id, event_type, account_id, subject, body)
            values (:eventId, 'reservation.reserved', :account, '제목', '본문')
            """,
        ).param("eventId", UUID.randomUUID()).param("account", account).update()

    private fun mailOf(reservationId: Long, eventType: String): Mail =
        jdbc.sql(
            """
            select n.subject, n.body, n.status
              from notification n join outbox o on o.event_id = n.event_id
             where o.aggregate_id = :id and n.event_type = :type
            """,
        ).param("id", reservationId).param("type", eventType).query(Mail::class.java).single()

    private fun mailCount(reservationId: Long): Long =
        jdbc.sql("select count(*) from notification n join outbox o on o.event_id = n.event_id where o.aggregate_id = :id")
            .param("id", reservationId).query(Long::class.java).single()

    data class Mail(val subject: String, val body: String, val status: String)

    /**
     * 안 나간 사건을 **브로커를 건너뛰고** 소비자에게 바로 건넨다(28).
     * 롤백 레인이라 진짜 Kafka 소비자는 이 트랜잭션의 행을 못 본다 — 배선은 `KafkaRelayTest` 가 잰다.
     */
    private fun deliver(): Int = PendingEvents(jdbcForEvents, jsonForEvents).deliver(store::record)
}
