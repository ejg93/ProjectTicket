package com.projectticket.ticket

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * compose·nginx 설정이 **말한 대로 도나**(35 가 찾은 결함 둘의 강제 지점).
 *
 * 둘 다 기동은 되고 오류도 안 내면서 틀린 것들이라, 부하를 재기 전엔 아무도 몰랐다:
 *
 * | 결함 | 어떻게 숨었나 | 여기서 막는 것 |
 * |---|---|---|
 * | `upstream { server app:8080; }` | 이름을 기동 때 한 번만 풀어 `--scale app=3` 이어도 **한 대만** 받았다 | 변수 `proxy_pass` + `resolver` 를 요구한다 |
 * | healthcheck 가 `wget` | JRE 이미지에 없어 컨테이너가 늘 `unhealthy` 였다(앱은 멀쩡) | 쓰는 도구가 이미지에 깔리나 본다 |
 * | Prometheus `static_configs: app:8080` | 긁을 때마다 셋 중 하나만 골라 대수별 카운터가 섞였다(`35h`) | `dns_sd_configs` 와 게이지 `max()` 를 요구한다 |
 *
 * 파일을 글자로 읽는 검사라 컨테이너가 필요 없다 — `StackVersionConsistencyTest` 와 같은 자리다.
 */
class ComposeContractTest {

    private val root: Path = Path.of("..").toAbsolutePath().normalize()
    private val compose = Files.readString(root.resolve("docker-compose.yml"))
    private val dockerfile = Files.readString(root.resolve("backend/Dockerfile"))
    private val prometheus = Files.readString(root.resolve("docker/prometheus/prometheus.yml"))
        .lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
    private val dashboard = Files.readString(root.resolve("docker/grafana/dashboards/ticket.json"))

    /** 주석은 걷어낸다 — 무엇을 안 쓰기로 했는지 설명하는 줄이 검사에 걸리면 안 된다 */
    private val nginx = Files.readString(root.resolve("docker/nginx/nginx.conf"))
        .lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")

    @Test
    fun the_gateway_resolves_the_app_name_on_every_request() {
        // 아래 셋은 「없어야 한다」 단언이라 읽을 것이 없으면 초록이다 — `proxy_pass` 가 다른 파일로 옮겨 가도 그렇다(`G3`).
        assertThat(nginx)
            .describedAs("nginx.conf 에서 proxy_pass 를 못 읽었다 — 설정을 다른 파일로 옮겼으면 이 검사도 같이 옮긴다")
            .contains("proxy_pass ")

        // `upstream` 블록은 이름을 기동 때 한 번만 푼다. 대수를 늘리려면 변수여야 한다.
        assertThat(nginx).doesNotContain("upstream ")
        assertThat(nginx).contains("resolver ")

        // 변수 없는 `proxy_pass http://이름:포트` 도 같은 함정이다.
        val fixedTarget = Regex("""proxy_pass\s+https?://[A-Za-z_][\w.-]*:\d+""")
        assertThat(fixedTarget.containsMatchIn(nginx))
            .describedAs("proxy_pass 의 호스트가 상수다 — 변수여야 매 요청에 다시 푼다")
            .isFalse()
    }

    @Test
    fun the_app_healthcheck_uses_a_tool_the_image_has() {
        val check = healthcheckOf("app")

        // JRE 이미지에는 `wget` 도 `curl` 도 없다. 쓰려면 Dockerfile 이 깔아야 한다.
        val tool = TOOLS.firstOrNull { check.contains("$it ") }
        assertThat(tool).describedAs("app healthcheck 가 아는 도구를 안 쓴다: $check").isNotNull()
        assertThat(dockerfile)
            .describedAs("healthcheck 가 `$tool` 을 쓰는데 Dockerfile 이 안 깐다")
            .contains("$tool ")
    }

    @Test
    fun the_app_healthcheck_asks_readiness() {
        // `liveness` 는 DB·Redis 를 안 본다(35a). 문이 붙일지 말지는 `readiness` 가 답한다.
        assertThat(healthcheckOf("app")).contains("/actuator/health/readiness")
    }

    @Test
    fun prometheus_scrapes_every_app_instance() {
        // `app:8080` 을 static 으로 두면 내장 DNS 가 돌려주는 하나만 긁는다 — 대가 셋이어도 시계열은 한 대씩 번갈아 온다.
        assertThat(prometheus).contains("dns_sd_configs:").contains("names: [\"app\"]").contains("port: 8080")
        assertThat(prometheus)
            .describedAs("compose 앱을 static 대상으로 두면 셋 중 하나만 긁는다(`35h`)")
            .doesNotContain("\"app:8080\"")
    }

    @Test
    fun grafana_gauges_do_not_sum_instances() {
        // 게이지 둘은 대마다 **같은** Redis 합을 보고한다 — 대가 여럿이면 시계열이 대수만큼 생겨 겹치거나, 더하면 대수배가 된다.
        assertThat(dashboard).contains("\"max(queue_length)\"").contains("\"max(queue_active)\"")
        assertThat(dashboard).doesNotContain("\"expr\": \"queue_length\"").doesNotContain("\"expr\": \"queue_active\"")
    }

    /** compose 의 그 서비스 healthcheck `test:` 한 줄. 두 칸 들여쓴 다음 서비스 머리 줄 전까지가 그 블록이다 */
    private fun healthcheckOf(service: String): String {
        val lines = compose.lines()
        val from = lines.indexOfFirst { it == "  $service:" }
        require(from >= 0) { "compose 에 $service 서비스가 없다" }

        val rest = lines.drop(from + 1)
        val until = rest.indexOfFirst { SERVICE_HEADER.matches(it) }
        val block = (if (until < 0) rest else rest.take(until)).joinToString("\n")

        return block.lines().firstOrNull { it.trimStart().startsWith("test:") }
            ?: error("$service 에 healthcheck 가 없다")
    }

    private companion object {
        /** compose 의 서비스 머리 줄 — 두 칸 들여쓰고 콜론으로 끝난다 */
        val SERVICE_HEADER = Regex("""^ {2}[a-z][\w-]*:$""")

        /** 컨테이너 안에서 HTTP 를 때릴 때 쓰는 것들. 이미지에 없으면 healthcheck 가 조용히 실패한다 */
        val TOOLS = listOf("curl", "wget")
    }
}
