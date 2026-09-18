package com.projectticket.ticket.queue

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.event.EventFixture
import com.projectticket.ticket.event.PerformanceOpenService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper

/**
 * 문이 지켜야 하는 것(`D12` 「입장」, 청크 22의 닫힘 조건).
 *
 * **강제 지점 둘이다.** 토큰 수명은 Redis TTL 이 지우고(코드가 지우는 것이 아니다), 정원·배치는 Lua 안에서만
 * 지켜진다 — 계산과 `ZPOPMIN` 이 갈리면 두 대가 같은 사람을 두 번 들인다. 둘 다 여기서 잰다.
 */
class AdmissionTest : PostgresTestBase() {

    @Autowired lateinit var queue: QueueService
    @Autowired lateinit var admission: AdmissionService
    @Autowired lateinit var scheduler: AdmissionScheduler
    @Autowired lateinit var openService: PerformanceOpenService
    @Autowired lateinit var redis: StringRedisTemplate
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: ObjectMapper

    private lateinit var fixture: EventFixture
    private var performanceId: Long = 0
    private var first: Long = 0
    private var second: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        val eventId = fixture.event(fixture.organizer("${PREFIX}org"))
        val hallId = fixture.hall(venueName = "${PREFIX}홀")
        fixture.seats(hallId, "F1-A", 2)
        fixture.mapSection(eventId, "F1-A", fixture.grade(eventId, "VIP", 154_000))
        performanceId = fixture.performance(eventId, hallId)
        openService.open(performanceId, actorAccountId = null)

        first = fixture.account("${PREFIX}1@test.local")
        second = fixture.account("${PREFIX}2@test.local")
    }

    @Test
    fun admits_from_the_front_and_empties_the_line() {
        queue.enter(performanceId, first)
        queue.enter(performanceId, second)

        assertThat(admission.admit(performanceId)).isEqualTo(2)
        assertThat(admission.tokenFor(performanceId, first)).isNotNull()
        assertThat(admission.tokenFor(performanceId, second)).isNotNull()
        assertThat(redis.opsForZSet().size(QueueKeys.waiting(performanceId))).isZero()
        // 들어온 사람은 정원을 차지한다. 이 수가 안 늘면 정원이 아무도 안 막는다.
        assertThat(redis.opsForZSet().size(QueueKeys.active(performanceId))).isEqualTo(2)
    }

    @Test
    fun the_token_names_the_account_and_the_performance() {
        queue.enter(performanceId, first)
        admission.admit(performanceId)

        val token = requireNotNull(admission.tokenFor(performanceId, first))
        val payload = json.readTree(redis.opsForValue().get(QueueKeys.admission(token)))

        // 관문(23)이 이 셋을 대조한다. 하나라도 빠지면 토큰 하나로 남의 계정·다른 회차를 산다.
        assertThat(payload["account_id"].asLong()).isEqualTo(first)
        assertThat(payload["performance_id"].asLong()).isEqualTo(performanceId)
        assertThat(payload["issued_at"].asLong()).isPositive()
    }

    @Test
    fun the_token_expires_on_its_own() {
        queue.enter(performanceId, first)
        admission.admit(performanceId)
        val token = requireNotNull(admission.tokenFor(performanceId, first))

        // 코드가 지우는 것이 아니라 Redis 가 지운다 — 스케줄러가 죽어도 토큰은 늙는다(`D12`).
        val ttl = AdmissionService.TOKEN_TTL.toSeconds()
        assertThat(redis.getExpire(QueueKeys.admission(token))).isBetween(ttl - 10, ttl)
        assertThat(redis.getExpire(QueueKeys.admissionByAccount(performanceId, first))).isBetween(ttl - 10, ttl)
    }

    @Test
    fun a_full_house_admits_nobody() {
        fillActive(AdmissionService.CAPACITY, expiresInMillis = 60_000)
        queue.enter(performanceId, first)

        assertThat(admission.admit(performanceId)).isZero()
        // 줄에 그대로 남아야 한다. 빼놓고 안 들이면 그 사람은 사라진다.
        assertThat(redis.opsForZSet().size(QueueKeys.waiting(performanceId))).isEqualTo(1)
        assertThat(admission.tokenFor(performanceId, first)).isNull()
    }

    @Test
    fun expired_tokens_give_the_capacity_back() {
        // 정원을 이미 죽은 토큰으로 채운다. 1단계가 이것을 걷어내지 않으면 줄이 영원히 안 준다.
        fillActive(AdmissionService.CAPACITY, expiresInMillis = -60_000)
        queue.enter(performanceId, first)

        assertThat(admission.admit(performanceId)).isEqualTo(1)
        assertThat(redis.opsForZSet().size(QueueKeys.active(performanceId))).isEqualTo(1)
    }

    @Test
    fun at_most_one_batch_enters_per_run() {
        // 배치보다 한 명 더 세운다. 한 번에 다 들이면 입장 속도 R 이 뜻을 잃는다(`D12` 「등식」).
        val over = AdmissionService.BATCH_SIZE.toInt() + 1
        repeat(over) { index ->
            redis.opsForZSet().add(QueueKeys.waiting(performanceId), "filler-$index", index.toDouble())
        }

        assertThat(admission.admit(performanceId)).isEqualTo(AdmissionService.BATCH_SIZE)
        assertThat(redis.opsForZSet().size(QueueKeys.waiting(performanceId))).isEqualTo(1)
    }

    @Test
    fun an_admitted_account_is_not_put_back_in_line() {
        queue.enter(performanceId, first)
        admission.admit(performanceId)

        // 새로고침이다. 다시 줄에 세우면 방금 얻은 자리를 스스로 버린다.
        val again = queue.enter(performanceId, first)

        assertThat(again.state).isEqualTo(QueueService.Position.ADMITTED)
        assertThat(again.admissionToken).isEqualTo(admission.tokenFor(performanceId, first))
        assertThat(again.rank).isNull()
        assertThat(redis.opsForZSet().size(QueueKeys.waiting(performanceId))).isZero()
    }

    @Test
    fun polling_hands_over_the_token() {
        queue.enter(performanceId, first)
        assertThat(queue.position(performanceId, first).state).isEqualTo(QueueService.Position.WAITING)

        // 스케줄러가 문을 연다. 화면은 폴링으로 그 사실을 안다 — 따로 알리는 경로가 없다(ADR 0003).
        assertThat(scheduler.admitDue()).isEqualTo(1)

        val after = queue.position(performanceId, first)
        assertThat(after.state).isEqualTo(QueueService.Position.ADMITTED)
        assertThat(after.admissionToken).isNotBlank()
    }

    /** 활성 집합을 채운다. `expiresInMillis` 가 음수면 이미 죽은 토큰이다 */
    private fun fillActive(count: Long, expiresInMillis: Long) {
        val expiry = (System.currentTimeMillis() + expiresInMillis).toDouble()
        repeat(count.toInt()) { index ->
            redis.opsForZSet().add(QueueKeys.active(performanceId), "taken-$index", expiry)
        }
    }

    private companion object {
        const val PREFIX = "admission-"
    }
}
