package com.projectticket.ticket.outbox

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.Waits
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.payment.MockPaymentGateway
import com.projectticket.ticket.payment.PaymentTransitionService
import com.projectticket.ticket.reservation.SeatHoldService
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.ObjectMapper

/**
 * 같은 사건이 두 번 와도 한 번만 처리하나(29의 닫힘 조건 절반, `D11`).
 *
 * **두 번 오는 것은 사고가 아니라 계약이다.** 발행이 at-least-once 고(릴레이가 보낸 뒤 표시 전에 죽으면 다시 보낸다),
 * 29 의 재시도까지 있어서 소비자는 **같은 사건을 여러 번 본다고 전제**해야 한다.
 *
 * 막는 자리는 앱 검증이 아니라 **제약**이다(`D11`) — 알림은 `(event_id, account_id)` 유일이라 둘째가 0행으로 끝난다.
 * 커밋 레인이다: 소비자가 다른 스레드에서 자기 트랜잭션으로 표를 읽는다.
 */
class ConsumerIdempotencyTest : ConcurrencyTestBase() {

    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var payments: PaymentTransitionService

    @Test
    fun the_same_event_twice_makes_one_notification() {
        val reserved = reservedSeat()
        val envelope = envelope(reserved.reservationId, reserved.accountId, reserved.performanceId)
        val message = json.writeValueAsString(envelope)

        // 같은 `event_id` 로 두 번. 릴레이가 재기동하며 다시 보내는 모양이다.
        kafka.send(EventTopics.RESERVATION, EventTopics.keyOf(reserved.reservationId), message).join()
        kafka.send(EventTopics.RESERVATION, EventTopics.keyOf(reserved.reservationId), message).join()

        Waits.until("알림이 생긴다", Duration.ofSeconds(30)) { notificationCount(envelope.eventId) >= 1 }
        // 두 번째가 행을 하나 더 만들면 같은 메일이 두 통 나간다.
        Thread.sleep(SETTLE_MS)
        assertThat(notificationCount(envelope.eventId)).isEqualTo(1)

        // 커밋 레인이라 이 행이 남는다. 스윕 전체를 세는 테스트가 있어서 자기 것은 자기가 치운다.
        jdbc.sql("delete from notification where event_id = :id").param("id", envelope.eventId).update()
    }

    /** 커밋된 예매 하나. 소비자가 표를 다시 읽으므로(`D11`) 진짜 행이 있어야 한다 */
    private fun reservedSeat(): Reserved {
        val fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("${PREFIX}consumer-idem"))
        val hallId = fixture.hall(venueName = "${PREFIX}consumer-idem-${System.nanoTime()}")
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        val performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)

        val accountId = fixture.account("${PREFIX}consumer-idem-${System.nanoTime()}@test.local")
        val seatId = fixture.performanceSeatIds(performanceId).first()
        val reservationId = seatHold.hold(accountId, SeatHoldService.Command(performanceId, listOf(seatId)))
        val paying = payments.startPaying(accountId, reservationId)
        payments.settle(accountId, paying, MockPaymentGateway.Result("M-${System.nanoTime()}", "4242", null))
        return Reserved(accountId, reservationId, performanceId)
    }

    data class Reserved(val accountId: Long, val reservationId: Long, val performanceId: Long)

    private fun envelope(reservationId: Long, accountId: Long, performanceId: Long) = OutboxRelay.Envelope(
        eventId = UUID.randomUUID(),
        type = EventType.RESERVATION_RESERVED,
        version = 1,
        occurredAt = OffsetDateTime.now(),
        aggregateType = AggregateType.RESERVATION,
        aggregateId = reservationId,
        // **카탈로그가 정한 필드 그대로**여야 한다(`D11`) — 알림이 금액·좌석 수를 이 안에서 읽는다.
        // 하나라도 빠지면 소비자가 죽고, 그것은 29 의 DLT 경로로 간다(그 길은 `DlqTest` 가 잰다).
        payload = mapOf(
            "reservation_id" to reservationId,
            "account_id" to accountId,
            "performance_id" to performanceId,
            "payment_id" to 1,
            "total_amount" to 154_000,
            "seat_count" to 1,
        ),
    )

    private fun notificationCount(eventId: UUID): Long =
        jdbc.sql("select count(*) from notification where event_id = :id")
            .param("id", eventId).query(Long::class.java).single()

    private companion object {
        /** 둘째 메시지가 처리될 시간을 준 뒤에 센다. 안 주면 「아직 안 왔다」를 「안 만들었다」로 읽는다 */
        const val SETTLE_MS = 3_000L
    }
}
