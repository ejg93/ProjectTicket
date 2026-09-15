package com.projectticket.ticket.health

import com.projectticket.ticket.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

class HealthControllerTest : PostgresTestBase() {

    @Autowired
    lateinit var mockMvc: MockMvc

    /**
     * 마이그레이션 수를 고정한다. 새 `V*.sql` 을 더하면 이 수를 같이 올린다 —
     * 올리는 것을 잊으면 여기서 빨개지고, 그것이 「마이그레이션이 실제로 적용됐나」를 기동 경로에서 재는 자리다.
     */
    @Test
    fun health_returns_applied_migrations() {
        mockMvc.get("/api/health")
            .andExpect {
                status { isOk() }
                jsonPath("$.app") { value("ticket-backend") }
                jsonPath("$.database") { value("test") }
                jsonPath("$.applied_migrations") { value(16) }
                jsonPath("$.checked_at") { exists() }
            }
    }
}
