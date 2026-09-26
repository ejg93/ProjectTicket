package com.projectticket.ticket.payment

import com.projectticket.ticket.PostgresTestBase
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * 결제 전에 보여 주는 구간표(`42-1a`, `D6`)가 **취소 계산과 같은 판**을 내나.
 *
 * 시작 판(`V10`)은 넷이다. 새 판을 넣으면 옛 판은 남고 표에서는 사라져야 한다 — 남아 섞이면 화면이 보여 준 율과 뗀 율이 갈린다.
 */
class RefundTierReadTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun anyone_reads_the_current_tiers_as_numbers() {
        mvc.get("/api/refund-tiers").andExpect {
            status { isOk() }
            jsonPath("$.tiers.length()") { value(4) }
            jsonPath("$.tiers[*].days_before_min") { value(contains(10, 7, 3, 1)) }
            // 율은 문자열이 아니라 숫자로 나간다 — 화면이 곱해 쓴다.
            jsonPath("$.tiers[1].rate") { isNumber() }
            jsonPath("$.tiers[1].rate") { value(0.10) }
        }
    }

    @Test
    fun a_new_edition_replaces_the_old_one_in_the_table() {
        // 시험 트랜잭션 안에서 `now()` 는 한 값이라 이 판이 곧바로 효력을 갖는다.
        jdbc.sql(
            """
            insert into refund_fee_tier (days_before_min, rate, effective_at)
            values (5, 0.15, now()), (2, 0.25, now()), (0, 0.50, now())
            """,
        ).update()

        mvc.get("/api/refund-tiers").andExpect {
            status { isOk() }
            jsonPath("$.tiers[*].days_before_min") { value(contains(5, 2, 0)) }
            jsonPath("$.tiers[2].rate") { value(0.50) }
        }
    }
}
