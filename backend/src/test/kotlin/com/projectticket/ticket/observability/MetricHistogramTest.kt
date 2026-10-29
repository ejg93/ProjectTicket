package com.projectticket.ticket.observability

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * 대시보드가 p95 를 읽는 지표에 버킷이 붙는가(`D10` 「지표」, 점검 11차 `I11-9`).
 *
 * **버킷이 없어도 아무 오류가 안 난다.** Prometheus 에 sum·count·max 만 나가고 `histogram_quantile` 패널이 조용히 빈다 —
 * 부하 4차(`doc/notes/load-4.md`)가 그렇게 비었다. 설정은 다른 프로필이 덮을 수 있어 실린 컨텍스트에서 긁어 본다.
 *
 * 테스트 컨텍스트는 기본으로 Prometheus 레지스트리를 안 띄운다(`SimpleMeterRegistry` 는 버킷을 안 만든다) —
 * `@AutoConfigureMetrics` 로 운영과 같은 레지스트리를 켠다. 컨텍스트가 하나 더 뜨는 값이다.
 */
@AutoConfigureMetrics
class MetricHistogramTest : PostgresTestBase() {

    @Autowired lateinit var metrics: TicketMetrics
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun the_dashboard_histograms_are_scraped_with_buckets() {
        metrics.seatHoldLatency(Duration.ofMillis(12))
        metrics.queueWaited(Duration.ofSeconds(3))
        mvc.get("/api/health")

        val scrape = mvc.get("/actuator/prometheus").andReturn().response.contentAsString

        // 대시보드가 읽는 이름 그대로 잰다 — 버킷이 나와도 패널의 이름과 다르면 똑같이 빈다.
        val panels = BUCKET.findAll(Files.readString(DASHBOARD)).map { it.value }.toSet()
        assertThat(panels).describedAs("`ticket.json` 에서 `_bucket` 을 하나도 못 읽었다").isNotEmpty()

        panels.forEach { bucket ->
            assertThat(scrape)
                .describedAs("$bucket 이 안 나온다 — `management.metrics.distribution.percentiles-histogram` 을 본다")
                .contains("$bucket{")
        }
    }

    private companion object {
        val DASHBOARD: Path = Path.of("../docker/grafana/dashboards/ticket.json")
        val BUCKET = Regex("""\w+_bucket""")
    }
}
