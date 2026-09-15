package com.projectticket.ticket.demo

import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.event.OrganizerEventService
import com.projectticket.ticket.event.PerformanceOpenService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * 손으로 밟아 볼 데모 데이터(12). **`local` 프로필에서만 돈다**(`ticket.demo.enabled`).
 *
 * **마이그레이션이 아니라 기동 러너다**(사용자 선택). 시드를 `V900` 대로 넣으면 그 번호가 적용 이력의 최고가 돼서
 * 다음 마이그레이션이 막힌다(`stack.md`). 여기서는 Flyway 이력이 안 더럽혀지고, **시드가 실제 서비스를 밟아서** 그 경로가 도는지도 같이 확인된다 —
 * 좌석 복제(9)·정책 박제(27)·등급 매핑(11)이 한 번씩 돈다.
 *
 * 대신 **멱등을 스스로 든다.** Flyway 가 공짜로 주던 「한 번만」이 없어서, 표식 기획사가 이미 있으면 통째로 건너뛴다.
 *
 * 공연장·홀·좌석은 SQL 로 넣는다 — 그것을 만드는 입구가 아직 없다(관리자 몫). 2천 석을 `generate_series` 한 문장으로 넣는 이유는
 * 행마다 왕복하면 2천 번이고 그 사이 트랜잭션이 열려 있어서다(`PerformanceOpenService.copySeats` 와 같은 판단).
 */
@Component
@ConditionalOnProperty(name = ["ticket.demo.enabled"], havingValue = "true")
class DemoSeeder(
    private val jdbc: JdbcClient,
    private val passwordEncoder: PasswordEncoder,
    private val eventService: OrganizerEventService,
    private val openService: PerformanceOpenService,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(DemoSeeder::class.java)

    @Transactional
    override fun run(args: ApplicationArguments) {
        if (alreadySeeded()) {
            log.info("데모 데이터 — 이미 있다")
            return
        }

        val organizerId = insertOrganizer()
        val organizerAccount = insertAccount("organizer@demo.local", "데모기획 담당", AccountRole.ORGANIZER)
        joinOrganizer(organizerId, organizerAccount)
        insertAccount("admin@demo.local", "관리자", AccountRole.ADMIN)
        repeat(AUDIENCE_COUNT) { insertAccount("audience${it + 1}@demo.local", "관객${it + 1}", AccountRole.AUDIENCE) }

        val hallIds = insertVenueAndHalls()
        val performanceIds = HALL_SECTIONS.keys.flatMapIndexed { index, _ -> seedEvent(organizerAccount, organizerId, hallIds, index) }

        log.info("데모 데이터 — 계정={} 홀={} 회차={}", AUDIENCE_COUNT + 2, hallIds.size, performanceIds.size)
    }

    /** 공연 하나 + 그 홀의 회차 둘. 하나는 열고 하나는 `draft` 로 둔다 — 여는 절차를 화면에서 밟아 볼 자리가 필요하다 */
    private fun seedEvent(accountId: Long, organizerId: Long, hallIds: List<Long>, index: Int): List<Long> {
        val hallId = hallIds[index]
        val sections = HALL_SECTIONS.values.toList()[index]
        val eventId = eventService.createEvent(
            accountId,
            OrganizerEventService.EventCommand(
                organizerId = organizerId,
                title = EVENT_TITLES[index],
                grades = listOf(
                    OrganizerEventService.GradeCommand("VIP", 154_000, sections.take(1)),
                    OrganizerEventService.GradeCommand("R", 121_000, sections.drop(1)),
                ),
            ),
        )

        val now = OffsetDateTime.now()
        return listOf(14L, 21L).mapIndexed { offset, days ->
            val performanceId = eventService.createPerformance(
                accountId,
                eventId,
                OrganizerEventService.PerformanceCommand(
                    hallId = hallId,
                    startsAt = now.plusDays(days),
                    salesOpenAt = now.minusDays(1),
                    salesCloseAt = null,
                ),
            )
            if (offset == 0) openService.open(performanceId, accountId)
            performanceId
        }
    }

    private fun alreadySeeded(): Boolean =
        jdbc.sql("select count(*) from organizer where code = :code").param("code", ORGANIZER_CODE).query(Long::class.java).single() > 0

    private fun insertOrganizer(): Long =
        jdbc.sql("insert into organizer (code, name) values (:code, '데모기획') returning organizer_id")
            .param("code", ORGANIZER_CODE).query(Long::class.java).single()

    /** 비밀번호가 하나인 것은 `local` 전용이라서다. 운영 경로로는 이 러너가 아예 안 뜬다(`@ConditionalOnProperty`) */
    private fun insertAccount(email: String, displayName: String, role: AccountRole): Long =
        jdbc.sql(
            """
            insert into account (email, password_hash, display_name, role)
            values (:email, :hash, :name, :role)
            returning account_id
            """,
        )
            .param("email", email)
            .param("hash", passwordEncoder.encode(DEMO_PASSWORD))
            .param("name", displayName)
            .param("role", role.code)
            .query(Long::class.java)
            .single()

    private fun joinOrganizer(organizerId: Long, accountId: Long) =
        jdbc.sql("insert into organizer_member (organizer_id, account_id) values (:organizer, :account)")
            .param("organizer", organizerId).param("account", accountId).update()

    /** 홀 둘에 좌석 2천 — 구역마다 열 20 × 번호 25 */
    private fun insertVenueAndHalls(): List<Long> {
        val venueId = jdbc.sql("insert into venue (name, address) values ('데모아트홀', '서울') returning venue_id")
            .query(Long::class.java).single()

        return HALL_SECTIONS.map { (hallName, sections) ->
            val hallId = jdbc.sql("insert into hall (venue_id, name) values (:venue, :name) returning hall_id")
                .param("venue", venueId).param("name", hallName).query(Long::class.java).single()

            sections.forEach { section ->
                jdbc.sql(
                    """
                    insert into seat (hall_id, section, row_label, seat_number)
                    select :hall, :section, chr(64 + r), n
                      from generate_series(1, :rows) r, generate_series(1, :seats) n
                    """,
                )
                    .param("hall", hallId).param("section", section)
                    .param("rows", ROWS_PER_SECTION).param("seats", SEATS_PER_ROW)
                    .update()
            }
            hallId
        }
    }

    companion object {
        /** 이미 시드됐나를 이 코드로 본다. Flyway 가 주던 「한 번만」을 대신하는 자리 */
        const val ORGANIZER_CODE = "demo-org"

        /** `local` 전용이라 하나로 둔다. NIST 최소 15자(`D9`)를 지킨다 — 로그인 화면에서 그대로 밟힌다 */
        const val DEMO_PASSWORD = "demo-password-1234"

        const val AUDIENCE_COUNT = 4

        private const val ROWS_PER_SECTION = 20
        private const val SEATS_PER_ROW = 25

        /** 홀 둘 × 구역 둘 × 500석 = 2천석 */
        private val HALL_SECTIONS = linkedMapOf(
            "1관" to listOf("F1-A", "F1-B"),
            "2관" to listOf("F2-A", "F2-B"),
        )

        private val EVENT_TITLES = listOf("겨울 콘서트", "봄 뮤지컬")
    }
}
