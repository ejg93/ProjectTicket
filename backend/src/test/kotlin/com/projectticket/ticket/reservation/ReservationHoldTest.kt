package com.projectticket.ticket.reservation

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import com.projectticket.ticket.queue.AdmissionService
import com.projectticket.ticket.queue.QueueGateFilter
import com.projectticket.ticket.queue.QueueService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 선점 입구의 계약(`D5`) — 상태 코드·`type`·추가 필드·멱등 재생. 경합은 `SeatHoldConcurrencyTest`, 불변식은 `ReservationSeatConsistencyTest` 가 잰다.
 *
 * 롤백 바탕이라 지연 트리거는 안 돈다. 여기서 보는 것은 응답 모양이다.
 * **실패한 선점이 아무것도 안 남기는 것도 여기서는 못 본다** — 서비스의 롤백이 이 테스트 트랜잭션에 rollback-only 표시만 남기고 행은 그대로 보인다(`stack.md`).
 * 그것은 `ReservationSeatConsistencyTest.losing_hold_leaves_nothing_behind` 가 커밋 레인에서 잰다.
 */
class ReservationHoldTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var queue: QueueService
    @Autowired lateinit var admission: AdmissionService

    private lateinit var fixture: EventFixture
    private lateinit var buyer: TicketUser
    private var eventId: Long = 0
    private var hallId: Long = 0
    private var performanceId: Long = 0
    private var seatIds: List<Long> = emptyList()

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        buyer = principal("buyer@test.local")
        eventId = fixture.event(fixture.organizer())
        hallId = fixture.hall()
        fixture.seats(hallId, "F1-A", 5)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)
        seatIds = fixture.performanceSeatIds(performanceId)
    }

    @Test
    fun hold_creates_a_reservation_and_marks_the_seats() {
        val body = hold(seatIds.take(2))
            .andExpect {
                status { isCreated() }
                header { string("Location", org.hamcrest.Matchers.startsWith("/api/reservations/")) }
                jsonPath("$.reservation_id") { isNumber() }
                jsonPath("$.performance_id") { value(performanceId) }
                jsonPath("$.status") { value("held") }
                jsonPath("$.total_amount") { value(308_000) }
                jsonPath("$.held_until") { exists() }
                jsonPath("$.paying_until") { value(null) }
                jsonPath("$.seats.length()") { value(2) }
                jsonPath("$.seats[0].price") { value(154_000) }
            }
            .andReturn().response.contentAsString
        val reservationId = json.readTree(body)["reservation_id"].asLong()

        assertThat(seatStatuses(reservationId)).containsExactly("held", "held")
        assertThat(
            jdbc.sql("select count(*) from audit_log where event_type = 'reservation.held' and target_id = :id")
                .param("id", reservationId).query(Long::class.java).single(),
        ).isOne()
    }

    @Test
    fun same_key_replays_the_same_reservation() {
        val key = UUID.randomUUID().toString()
        val first = hold(seatIds.take(1), key = key).andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val second = hold(seatIds.take(1), key = key).andExpect { status { isCreated() } }.andReturn().response.contentAsString

        // 재전송이 둘째 예매를 만들면 같은 사람이 좌석을 두 벌 쥔다(`D4`).
        assertThat(json.readTree(second)["reservation_id"]).isEqualTo(json.readTree(first)["reservation_id"])
        assertThat(jdbc.sql("select count(*) from reservation where performance_id = :id").param("id", performanceId).query(Long::class.java).single()).isOne()
    }

    @Test
    fun same_key_with_a_different_body_is_rejected() {
        val key = UUID.randomUUID().toString()
        hold(seatIds.take(1), key = key).andExpect { status { isCreated() } }

        hold(seatIds.drop(1).take(1), key = key).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:idempotency-key-reused") }
        }
    }

    @Test
    fun missing_key_is_validation_failed() {
        // 형식도 본다 — UUIDv4 가 아니면 같은 400 이다(`D4`).
        hold(seatIds.take(1), key = "not-a-uuid").andExpect { status { isBadRequest() } }
        hold(seatIds.take(1), key = null).andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:validation-failed") }
            jsonPath("$.errors[0].field") { value("Idempotency-Key") }
        }
    }

    @Test
    fun more_than_four_seats_is_over_limit() {
        hold(seatIds.take(5)).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:over-limit") }
            jsonPath("$.limit") { value(4) }
            jsonPath("$.requested") { value(5) }
        }
    }

    @Test
    fun seat_of_another_performance_is_rejected() {
        hold(listOf(seatIds[0], -1)).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:seat-not-in-performance") }
            jsonPath("$.seat_ids[0]") { value(-1) }
        }
    }

    @Test
    fun taken_seat_is_a_conflict_and_nothing_is_held() {
        fixture.hold(fixture.account("other@test.local"), performanceId, "F1-A", 1)

        hold(seatIds.take(2)).andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:seat-taken") }
            jsonPath("$.taken_seat_ids[0]") { value(seatIds[0]) }
            jsonPath("$.taken_seat_ids.length()") { value(1) }
        }
    }

    @Test
    fun draft_performance_is_not_open() {
        val draft = fixture.performance(eventId, hallId)

        hold(seatIds.take(1), performance = draft).andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:performance-not-open") }
            jsonPath("$.performance_status") { value("draft") }
        }
    }

    @Test
    fun someone_elses_reservation_is_not_found() {
        val body = hold(seatIds.take(1)).andReturn().response.contentAsString
        val reservationId = json.readTree(body)["reservation_id"].asLong()

        mvc.get("/api/reservations/$reservationId") { with(user(principal("stranger@test.local"))) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.type") { value("tag:projectticket.example,2026:reservation-not-found") }
            }
        mvc.get("/api/reservations/$reservationId") { with(user(buyer)) }
            .andExpect { status { isOk() }; jsonPath("$.reservation_id") { value(reservationId) } }
    }

    @Test
    fun hold_requires_login() {
        mvc.post("/api/performances/$performanceId/reservations") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("seat_ids" to seatIds.take(1)))
            header("Idempotency-Key", "k")
        }.andExpect { status { isUnauthorized() } }
    }

    private fun hold(seats: List<Long>, key: String? = UUID.randomUUID().toString(), performance: Long = performanceId): ResultActionsDsl =
        mvc.post("/api/performances/$performance/reservations") {
            with(user(buyer))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("seat_ids" to seats))
            if (key != null) header("Idempotency-Key", key)
            admissionToken(performance)?.let { header(QueueGateFilter.ADMISSION_HEADER, it) }
        }

    /**
     * 관문(23)을 지날 입장권. 줄이 비어 있으면 진입이 곧 입장이다(`D12` 「항상 켠다」).
     *
     * 성공한 선점은 토큰을 반납하므로 요청마다 새로 받는다. 판매 중이 아닌 회차는 줄 자체가 없어서 null 이고,
     * 그 요청은 관문이 아니라 선점 서비스가 거절한다 — 그것을 재는 테스트들이 여기 있다.
     */
    private fun admissionToken(performance: Long): String? =
        runCatching {
            queue.enter(performance, buyer.id)
            admission.tokenFor(performance, buyer.id)
        }.getOrNull()

    /** DB 에 실제 계정이 있어야 한다 — 예매가 계정을 외래키로 잡는다 */
    private fun principal(email: String): TicketUser =
        TicketUser(fixture.account(email), email, AccountRole.AUDIENCE, passwordHash = null, active = true)

    private fun seatStatuses(reservationId: Long): List<String> =
        jdbc.sql("select status from performance_seat where reservation_id = :id").param("id", reservationId)
            .query(String::class.java).list().filterNotNull()
}
