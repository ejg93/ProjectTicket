package com.projectticket.ticket.reservation

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.idempotency.IdempotencyService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.time.OffsetDateTime

/**
 * 선점의 업무 사건이 로그에 남는가(`D10` 「무엇을 찍나」, 점검 11차 `I11-12`).
 *
 * **로그가 빠져도 아무 시험이 안 빨개진다.** 선점은 하루에 가장 많이 도는 사건인데 로그 호출이 0 이었고,
 * 교착 재시도는 조용히 돌아서 잦아져도 흔적이 없었다. 확정·오픈·관객 취소 셋은 문서가 들고 이 둘만 여기서 잰다.
 */
class ReservationLogTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var seatHold: SeatHoldService
    @Autowired lateinit var openService: PerformanceOpenService

    private val attached = mutableListOf<Pair<Logger, ListAppender<ILoggingEvent>>>()

    @AfterEach
    fun detach() {
        attached.forEach { (logger, appender) -> logger.detachAppender(appender) }
    }

    @Test
    fun a_hold_is_logged_with_identifiers_only() {
        val captured = capture(SeatHoldService::class.java)
        val fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer())
        val hallId = fixture.hall()
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        val performanceId = fixture.performance(eventId, hallId).also { openService.open(it, actorAccountId = null) }

        val reservationId = seatHold.hold(
            fixture.account("hold-log@test.local"),
            SeatHoldService.Command(performanceId, fixture.performanceSeatIds(performanceId)),
        )

        assertThat(captured.list.filter { it.level == Level.INFO }.map { it.formattedMessage })
            .contains("좌석 선점 reservation_id=$reservationId performance_id=$performanceId seats=2")
            // 개인정보는 식별자만(`D10`)
            .noneMatch { it.contains("@") }
    }

    @Test
    fun a_deadlock_retry_is_a_warning_with_the_seats() {
        val captured = capture(ReservationService::class.java)
        val service = ReservationService(
            idempotency = DeadlockOnce(),
            seatHold = mock(SeatHoldService::class.java),
            reservationQuery = mock(ReservationQuery::class.java),
        )

        service.hold(accountId = 1, idempotencyKey = "deadlock-log", command = SeatHoldService.Command(performanceId = 7, seatIds = listOf(11, 12)))

        assertThat(captured.list.filter { it.level == Level.WARN }.map { it.formattedMessage })
            .containsExactly("교착 재시도 1/1 sqlstate=40P01 seat_ids=[11, 12]")
    }

    private fun capture(type: Class<*>): ListAppender<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(type) as Logger
        return ListAppender<ILoggingEvent>().apply { start() }.also { appender ->
            logger.addAppender(appender)
            attached += logger to appender
        }
    }

    /** 첫 시도는 `40P01` 로 지고 둘째는 답을 준다. 작업 본문은 안 부른다 — 재시도 분기만 잰다 */
    private class DeadlockOnce : IdempotencyService(mock(JdbcClient::class.java), JsonMapper()) {
        private var calls = 0

        override fun <T : Any> run(accountId: Long, key: String, request: Any, responseType: Class<T>, work: () -> T): T {
            calls += 1
            if (calls == 1) throw DataAccessResourceFailureException("deadlock", SQLException("deadlock detected", "40P01"))
            return responseType.cast(
                ReservationQuery.Reservation(
                    reservationId = 1,
                    performanceId = 7,
                    status = "held",
                    totalAmount = 0,
                    heldUntil = OffsetDateTime.now(),
                    payingUntil = null,
                    seats = emptyList(),
                ),
            )
        }
    }
}
