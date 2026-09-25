package com.projectticket.ticket.health

import com.projectticket.ticket.PostgresTestBase
import java.io.File
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

class HealthControllerTest : PostgresTestBase() {

    @Autowired
    lateinit var mockMvc: MockMvc

    /**
     * 적용된 마이그레이션 수가 `db/migration` 의 `V*.sql` 파일 수와 같은지 본다 —
     * 그것이 「마이그레이션이 실제로 전부 적용됐나」를 기동 경로에서 재는 자리다.
     * 수를 손으로 적지 않는다(`B0-4`). 적었을 때는 새 `V*` 마다 사람이 올려야 했고 불변식은 같았다.
     */
    @Test
    fun health_returns_applied_migrations() {
        val migrationFiles = File("src/main/resources/db/migration").listFiles()
            ?.count { it.name.matches(MIGRATION_FILE) }
            ?: error("db/migration 이 없다 — 테스트의 작업 디렉터리가 backend/ 가 아니다")
        check(migrationFiles > 0) { "db/migration 에 V*.sql 이 하나도 없다" }

        mockMvc.get("/api/health")
            .andExpect {
                status { isOk() }
                jsonPath("$.app") { value("ticket-backend") }
                jsonPath("$.database") { value("test") }
                jsonPath("$.applied_migrations") { value(migrationFiles) }
                jsonPath("$.checked_at") { exists() }
            }
    }

    private companion object {
        /** Flyway 의 버전 마이그레이션 이름. `U*`·`R*` 은 안 센다 — 적용 수에 안 든다. */
        val MIGRATION_FILE = Regex("""V\d+__.*\.sql""")
    }
}
