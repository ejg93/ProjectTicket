package com.projectticket.ticket.outbox

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.notification.ReservationNotificationListener
import com.projectticket.ticket.payment.MockPaymentGateway
import com.projectticket.ticket.payment.PaymentTransitionService
import com.projectticket.ticket.reservation.SeatHoldService
import com.projectticket.ticket.settlement.SettlementListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

/**
 * 소비자가 봉투의 판과 계정을 `D11` 대로 다루나 — **아는 판만 처리하고**(「버전」 — 두 판을 같이 내는 동안 모르는 판은 건너뛴다),
 * 받는 사람은 페이로드가 아니라 **표에서 읽는다**(「소비」).
 *
 * 리스너를 직접 부른다 — 판 검사는 리스너 입구에 있고 `PendingEvents` 는 그 뒤의 `store` 를 부른다.
 * 커밋 레인이다 — 소비자가 `REQUIRES_NEW` 라 롤백 레인의 미커밋 예매를 못 본다(`NotificationIdempotencyTest` 와 같은 이유).
 */
class ConsumerVersionTest : ConcurrencyTestBase() {

    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var notifications: ReservationNotificationListener
    @Autowired lateinit var settlements: SettlementListener
    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var payments: PaymentTransitionService
    @Autowired lateinit var openService: PerformanceOpenService

    private lateinit var fixture: EventFixture
    private var accountId: Long = 0
    private var eventId: Long = 0
    private var hallId: Long = 0

    private val logger = LoggerFactory.getLogger(EnvelopeVersions::class.java) as Logger
    private val captured = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun setUp() {
        logger.addAppender(captured)
        fixture = EventFixture(jdbc)
        accountId = fixture.account("${PREFIX}version@test.local")
        eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 1)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(captured)
    }

    @Test
    fun an_unknown_version_is_skipped_with_one_info_line_per_consumer() {
        val envelope = reservedEnvelope().copy(version = EventType.SUPPORTED_VERSION + 1)

        notifications.on(json.writeValueAsString(envelope))
        settlements.on(json.writeValueAsString(envelope))

        assertThat(notificationCount(envelope)).describedAs("모르는 판은 알림을 안 만든다").isZero()
        val lines = captured.list.filter { it.level == Level.INFO }.map { it.formattedMessage }
        assertThat(lines).hasSize(2)
        // 식별자만(`D10`) — 페이로드는 안 찍는다
        assertThat(lines).allSatisfy {
            assertThat(it).contains(envelope.eventId.toString(), "reservation.reserved", "version=2").doesNotContain("total_amount")
        }
    }

    @Test
    fun the_recipient_comes_from_the_reservation_not_the_payload() {
        val stranger = fixture.account("${PREFIX}stranger@test.local")
        val envelope = reservedEnvelope().let { it.copy(payload = it.payload + ("account_id" to stranger)) }

        notifications.on(json.writeValueAsString(envelope))

        val recipient = jdbc.sql("select account_id from notification where event_id = :event")
            .param("event", envelope.eventId)
            .query(Long::class.java)
            .single()
        assertThat(recipient).describedAs("표가 이긴다 — 페이로드의 계정이 아니라 예매의 계정이다").isEqualTo(accountId)
    }

    /** 확정 예매를 하나 만들고 그 사건 봉투를 꺼낸다. 발행 표시는 `PendingEvents` 가 남긴다 */
    private fun reservedEnvelope(): OutboxRelay.Envelope {
        val performanceId = fixture.performance(eventId, hallId).also { openService.open(it, actorAccountId = null) }
        val reservationId = seatHold.hold(accountId, SeatHoldService.Command(performanceId, fixture.performanceSeatIds(performanceId)))
        payments.settle(
            accountId,
            payments.startPaying(accountId, reservationId),
            MockPaymentGateway.Result(approvalNumber = "V-${System.nanoTime()}", cardLast4 = "4242", declineReason = null),
        )
        val envelopes = mutableListOf<OutboxRelay.Envelope>()
        PendingEvents(jdbc, json).deliver { envelopes += it }
        return envelopes.single { it.type == EventType.RESERVATION_RESERVED && it.aggregateId == reservationId }
    }

    private fun notificationCount(envelope: OutboxRelay.Envelope): Long =
        jdbc.sql("select count(*) from notification where event_id = :event")
            .param("event", envelope.eventId)
            .query(Long::class.java)
            .single()
}
