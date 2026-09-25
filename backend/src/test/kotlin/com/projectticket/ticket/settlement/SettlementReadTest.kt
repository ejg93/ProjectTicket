package com.projectticket.ticket.settlement

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.event.EventFixture
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * 기획사가 회차 정산서를 읽는가(`45c`). **남의 회차는 404** · **합계가 항목과 같다** · 정산서가 아직 없으면 같은 404.
 *
 * 정산서는 정산 스윕이 만든다(27). 여기서는 읽기만 재므로 행을 직접 넣는다 — 합계=항목 합은 커밋 시점 지연 트리거가 드는데
 * 이 테스트는 롤백하니 그 트리거까지 가지 않는다. 그래서 넣는 값 자체를 맞춘다.
 */
class SettlementReadTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient

    private lateinit var fixture: EventFixture
    private lateinit var mine: TicketUser
    private lateinit var stranger: TicketUser
    private var performanceId: Long = 0

    @BeforeEach
    fun setUp() {
        fixture = EventFixture(jdbc)
        val myOrganizer = fixture.organizer("settle-mine")
        mine = organizerUser("settle-mine@test.local", myOrganizer)
        stranger = organizerUser("settle-other@test.local", fixture.organizer("settle-other"))
        performanceId = fixture.performance(fixture.event(myOrganizer), fixture.hall())
    }

    @Test
    fun my_settlement_reads_with_lines_that_sum_to_the_amount() {
        val settlementId = jdbc.sql(
            """
            insert into settlement (performance_id, settlement_policy_id, settle_at, status, amount, settled_at)
            values (:performance, (select settlement_policy_id from settlement_policy where organizer_id is null order by effective_at desc limit 1),
                    now(), 'pending', 90000, now())
            returning settlement_id
            """,
        ).param("performance", performanceId).query(Long::class.java).single()
        jdbc.sql("insert into settlement_line (settlement_id, kind, amount) values (:id, 'sale', 100000), (:id, 'platform_fee', -10000)")
            .param("id", settlementId).update()

        mvc.get("/api/organizer/settlements?performanceId=$performanceId") { with(user(mine)) }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("pending") }
            jsonPath("$.amount") { value(90_000) }
            jsonPath("$.lines.length()") { value(2) }
            jsonPath("$.lines[0].kind") { value("sale") }
            jsonPath("$.lines[1].amount") { value(-10_000) }
        }
    }

    @Test
    fun someone_elses_or_unsettled_performance_is_one_404() {
        // 정산서가 아직 없는 내 회차와 남의 회차가 한 이름이다 — 남의 회차 존재를 안 흘린다.
        mvc.get("/api/organizer/settlements?performanceId=$performanceId") { with(user(mine)) }.andExpect {
            status { isNotFound() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:performance-not-found") }
        }
        mvc.get("/api/organizer/settlements?performanceId=$performanceId") { with(user(stranger)) }.andExpect { status { isNotFound() } }
    }

    private fun organizerUser(email: String, organizerId: Long): TicketUser {
        val accountId = jdbc.sql(
            "insert into account (email, password_hash, display_name, role) values (:email, 'x', '담당자', 'organizer') returning account_id",
        ).param("email", email).query(Long::class.java).single()
        jdbc.sql("insert into organizer_member (organizer_id, account_id) values (:organizer, :account)")
            .param("organizer", organizerId).param("account", accountId).update()
        return TicketUser(accountId, email, AccountRole.ORGANIZER, null, true)
    }
}
