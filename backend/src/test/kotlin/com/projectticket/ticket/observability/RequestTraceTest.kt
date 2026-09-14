package com.projectticket.ticket.observability

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper

/**
 * 로그 한 줄에서 요청 하나를 되찾을 수 있는가(`D10`).
 *
 * 추적 ID 는 로그와 응답이 같아야 뜻이 있다. 사용자가 오류 화면의 ID 를 불러 줬는데 그 값으로 로그를 못 찾으면
 * ID 를 내려 준 의미가 없다. 두 자리가 각자 ID 를 만들면 그 일이 조용히 일어난다 —
 * 응답에도 값이 있고 로그에도 값이 있어서 눈으로는 정상으로 보인다.
 *
 * 패턴 문자열 자체는 안 본다. 그건 `logback-spring.xml` 의 몫이고 여기서 고정하면 형식을 바꿀 때마다 테스트가 깨진다.
 * 여기서 보는 것은 MDC 에 값이 실려 있는가와 그 값이 응답과 이어지는가다.
 */
class RequestTraceTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    private lateinit var captured: ListAppender<ILoggingEvent>

    @BeforeEach
    fun captureLogs() {
        captured = ListAppender<ILoggingEvent>().apply { start() }
        rootLogger().addAppender(captured)
    }

    @AfterEach
    fun stopCapturing() {
        rootLogger().detachAppender(captured)
        captured.stop()
    }

    @Test
    fun trace_id_rides_on_every_line() {
        mvc.get("/api/health").andExpect { status { isOk() } }

        // 한 줄이라도 비면 그 줄만 어느 요청의 것인지 모르게 된다.
        assertThat(linesFromRequest()).isNotEmpty
            .allSatisfy { assertThat(it.mdcPropertyMap["traceId"]).isNotBlank() }
    }

    @Test
    fun continues_incoming_traceparent() {
        mvc.get("/api/health") { header("traceparent", TRACEPARENT) }.andExpect { status { isOk() } }

        // 자기 ID 를 새로 뽑으면 프록시·프론트 쪽 로그와 안 이어진다(W3C Trace Context).
        assertThat(traceIdsInLog()).contains(INCOMING_TRACE_ID)
    }

    @Test
    fun stays_distinct_within_the_same_second() {
        mvc.get("/api/health").andExpect { status { isOk() } }
        mvc.get("/api/health").andExpect { status { isOk() } }

        // Brave 의 앞 8자리는 생성 시각이다. 앞자리를 찍으면 두 요청이 로그에서 같아 보인다.
        assertThat(traceIdsInLog().map { it.takeLast(SHOWN_IN_LOG) })
            .hasSize(2)
            .doesNotHaveDuplicates()
    }

    @Test
    fun error_response_carries_the_same_id_as_the_log() {
        // 인증 없이 막히는 경로다. 보안 필터가 끊는 자리라 응답 본문을 ProblemFactory 가 만든다.
        val body = mvc.get("/api/me").andExpect { status { isUnauthorized() } }
            .andReturn().response.contentAsString

        // 사용자가 불러 준 ID 로 로그를 못 찾으면 ID 를 내려 준 뜻이 없다.
        assertThat(json.readTree(body)["trace_id"].asString()).isIn(traceIdsInLog())
    }

    @Test
    fun request_line_has_method_path_status_and_duration() {
        mvc.get("/api/health").andExpect { status { isOk() } }

        assertThat(messagesFrom(RequestLogFilter::class.java))
            .anySatisfy { assertThat(it).startsWith("GET /api/health 200 ").endsWith("ms") }
    }

    @Test
    fun health_check_leaves_no_line() {
        mvc.get("/actuator/health")

        // 30초마다 두드리는 것을 남기면 진짜 요청이 그 사이에 묻힌다.
        assertThat(messagesFrom(RequestLogFilter::class.java)).isEmpty()
    }

    /** 요청 안에서 찍힌 줄만 고른다. 기동 로그에는 추적 문맥이 없다 */
    private fun linesFromRequest(): List<ILoggingEvent> =
        captured.list.filter { it.mdcPropertyMap.containsKey("traceId") }

    private fun traceIdsInLog(): List<String> =
        linesFromRequest().mapNotNull { it.mdcPropertyMap["traceId"] }.distinct()

    private fun messagesFrom(source: Class<*>): List<String> =
        captured.list.filter { it.loggerName == source.name }.map { it.formattedMessage }

    private fun rootLogger(): ch.qos.logback.classic.Logger =
        LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger

    private companion object {
        /** 밖에서 온 것처럼 보내는 W3C 헤더. 가운데 32자리가 trace-id 다 */
        const val INCOMING_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
        const val TRACEPARENT = "00-$INCOMING_TRACE_ID-00f067aa0ba902b7-01"

        /** 로그 패턴이 잘라 찍는 자릿수. `logback-spring.xml` 의 `%.6X{traceId}` 다 */
        const val SHOWN_IN_LOG = 6
    }
}
