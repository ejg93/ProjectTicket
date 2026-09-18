package com.projectticket.ticket.health

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * 프로브 둘이 서로 다른 질문에 답하나(35a의 닫힘 조건).
 *
 * **「살아 있나」와 「지금 트래픽을 받아도 되나」는 다른 질문이다.** DB 가 끊긴 인스턴스는 살아 있지만 일을 못 한다 —
 * 그때 답이 재시작(liveness 실패)이면 멀쩡한 프로세스를 죽이는 것이고, 트래픽 차단(readiness 실패)이어야 남은 대가 받는다.
 *
 * 끊긴 상태는 **우리가 만든 인디케이터**로 만든다. 컨테이너를 멈추면 Hikari 의 연결 대기(기본 30초)가 먼저 걸려서
 * 재는 것이 그룹 구성이 아니라 그 시간이 된다.
 */
@SpringBootTest(
    properties = [
        "ticket.scheduling.enabled=false",
        "management.endpoint.health.group.readiness.include=readinessState,db,redis,fake",
        "management.endpoint.health.show-details=always",
    ],
)
@Import(ReadinessTest.FakeComponent::class)
class ReadinessTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun readiness_watches_the_things_we_need_to_serve() {
        FakeComponent.up = true

        mvc.get("/actuator/health/readiness").andExpect {
            status { isOk() }
            // DB·Redis 가 그룹에 없으면 끊긴 인스턴스가 계속 트래픽을 받는다.
            jsonPath("$.components.db.status") { value("UP") }
            jsonPath("$.components.redis.status") { value("UP") }
        }
    }

    @Test
    fun a_broken_component_takes_readiness_down() {
        FakeComponent.up = false

        // 503 이라야 문(nginx·k8s)이 이 대를 뺀다. 200 이면 아무도 모른다.
        mvc.get("/actuator/health/readiness").andExpect { status { isServiceUnavailable() } }
    }

    @Test
    fun liveness_does_not_care_about_dependencies() {
        FakeComponent.up = false

        // DB 가 끊겼다고 프로세스를 죽이면 안 된다 — 재시작해도 DB 는 그대로다.
        mvc.get("/actuator/health/liveness").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }

    /** 끊긴 의존을 흉내 내는 자리. 테스트가 값을 바꿔 그룹이 어떻게 접히는지 본다 */
    @TestConfiguration(proxyBeanMethods = false)
    class FakeComponent {

        @Bean("fake")
        fun fake(): HealthIndicator = HealthIndicator { if (up) Health.up().build() else Health.down().build() }

        companion object {
            @JvmStatic
            var up: Boolean = true
        }
    }
}
