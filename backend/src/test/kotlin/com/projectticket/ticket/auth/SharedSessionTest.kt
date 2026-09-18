package com.projectticket.ticket.auth

import com.projectticket.ticket.PostgresTestBase
import com.projectticket.ticket.TicketApplication
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 인스턴스 둘이 같은 세션 쿠키를 인정하나(`20a` 의 닫힘 조건, ADR 0004).
 *
 * 세션이 톰캣 메모리에 있으면 한쪽에서 로그인한 쿠키를 다른 쪽이 모른다 — 인스턴스를 셋으로 늘리는 청크(33)가
 * 제일 먼저 걸리는 자리다. **강제 지점이 테스트인 이유**는 세션이 런타임 저장소라 타입으로도 DB 제약으로도 못 막아서다.
 *
 * 여기만 [PostgresTestBase] 를 안 쓴다. 그 바탕은 컨텍스트 하나에 `MockMvc` 라 「인스턴스 둘」을 만들 수가 없다 —
 * 대신 컨테이너 정의는 그 바탕의 것을 그대로 불러 쓴다(이미지가 갈리면 이 테스트만 다른 Redis 를 본다).
 * 앱을 두 번 띄우므로 느리다. `db` 태그로 느린 레인에 둔다.
 */
@Tag("db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SharedSessionTest {

    private val containers = PostgresTestBase.Containers()
    private val postgres = containers.postgres()
    private val redis = containers.redis()
    private val client: HttpClient = HttpClient.newHttpClient()

    private lateinit var instanceA: ConfigurableApplicationContext
    private lateinit var instanceB: ConfigurableApplicationContext
    private var portA = 0
    private var portB = 0

    @BeforeAll
    fun startTwoInstances() {
        postgres.start()
        redis.start()
        portA = freePort()
        portB = freePort()
        instanceA = boot(portA)
        instanceB = boot(portB)
    }

    @AfterAll
    fun stopTwoInstances() {
        // 이 테스트는 롤백이 없다 — 진짜 포트로 HTTP 를 보내니 커밋된다. 남긴 계정을 손으로 지운다(동의는 cascade).
        instanceA.getBean(JdbcClient::class.java)
            .sql("delete from account where email = :email").param("email", EMAIL).update()
        instanceA.close()
        instanceB.close()
        redis.stop()
        postgres.stop()
    }

    @Test
    fun login_on_one_context_is_seen_by_another() {
        val xsrf = cookieOf(request(portA, "GET", "/api/health"), "XSRF-TOKEN")
        request(portA, "POST", "/api/auth/signup", SIGNUP, mapOf("XSRF-TOKEN" to xsrf))
        val login = request(portA, "POST", "/api/auth/login", LOGIN, mapOf("XSRF-TOKEN" to xsrf))
        assertThat(login.statusCode()).isEqualTo(200)

        // A 에서 받은 세션 쿠키 하나만 들고 B 로 간다. 세션이 JVM 안에 있으면 여기서 401 이다.
        val me = request(portB, "GET", "/api/me", cookies = mapOf("TICKETSESSION" to cookieOf(login, "TICKETSESSION")))
        assertThat(me.statusCode()).isEqualTo(200)
        assertThat(me.body()).contains(EMAIL)
        // 쿠키 계약도 여기서 잰다(`D9`). 자동설정에 맡기면 배포 모양에 따라 이름·속성이 달라진다(`SecurityConfig.cookieSerializer`).
        assertThat(login.headers().allValues("set-cookie"))
            .anyMatch { it.startsWith("TICKETSESSION=") && it.contains("HttpOnly") && it.contains("SameSite=Lax") }
    }

    @Test
    fun logout_on_one_context_ends_it_on_the_other() {
        val xsrf = cookieOf(request(portA, "GET", "/api/health"), "XSRF-TOKEN")
        request(portA, "POST", "/api/auth/signup", SIGNUP, mapOf("XSRF-TOKEN" to xsrf))
        val login = request(portA, "POST", "/api/auth/login", LOGIN, mapOf("XSRF-TOKEN" to xsrf))
        val session = cookieOf(login, "TICKETSESSION")
        // 로그인은 CSRF 토큰을 버리기만 한다. 새 토큰은 다음 GET 에서 받는다 — 화면도 그렇게 돈다.
        val rotated = cookieOf(request(portB, "GET", "/api/me", cookies = mapOf("TICKETSESSION" to session)), "XSRF-TOKEN")

        val loggedOut = request(
            portB,
            "POST",
            "/api/auth/logout",
            "",
            mapOf("TICKETSESSION" to session, "XSRF-TOKEN" to rotated),
        )
        assertThat(loggedOut.statusCode()).isEqualTo(204)

        // 끊은 곳이 B 인데 A 도 같이 끊긴다. 저장소가 하나라서다 — 정지·탈퇴(`5a`·`5b`)가 기대는 성질이 이것이다.
        val me = request(portA, "GET", "/api/me", cookies = mapOf("TICKETSESSION" to session))
        assertThat(me.statusCode()).isEqualTo(401)
    }

    /**
     * 컨테이너는 둘이 같이 쓴다. 서로 다른 Redis 를 보면 이 테스트가 증명할 것이 없다.
     *
     * 설정을 **명령행 인자로** 넘긴다. `properties()` 는 기본값이라 `application.yml` 이 이기고,
     * 그러면 컨테이너가 아니라 로컬 5432 를 찾다가 인증에서 죽는다.
     */
    private fun boot(port: Int): ConfigurableApplicationContext =
        SpringApplicationBuilder(TicketApplication::class.java).run(
            "--server.port=$port",
            "--ticket.scheduling.enabled=false",
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--spring.data.redis.host=${redis.host}",
            "--spring.data.redis.port=${redis.getMappedPort(6379)}",
        )

    /**
     * 쿠키를 자동으로 안 모은다. 무엇이 인스턴스를 넘어갔나가 이 테스트의 전부라 부르는 쪽이 매번 적는다.
     * CSRF 토큰은 쿠키에 있는 값을 헤더에도 싣는다(`SecurityConfig` 의 SPA 구성).
     */
    private fun request(
        port: Int,
        method: String,
        path: String,
        body: String? = null,
        cookies: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
        if (cookies.isNotEmpty()) {
            builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        cookies["XSRF-TOKEN"]?.let { builder.header("X-XSRF-TOKEN", it) }
        val request = when (method) {
            "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body.orEmpty()))
            else -> builder.GET()
        }.build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /**
     * 마지막으로 값이 들어 있는 것을 고른다. 토큰을 회전시키면 지우는 쿠키(빈 값)와 새 쿠키가 같이 내려와서
     * 앞에서부터 찾으면 빈 값을 집는다 — 로그인 응답의 `XSRF-TOKEN` 이 그 모양이다.
     */
    private fun cookieOf(response: HttpResponse<String>, name: String): String =
        response.headers().allValues("set-cookie")
            .filter { it.startsWith("$name=") }
            .map { it.substringAfter('=').substringBefore(';') }
            .lastOrNull { it.isNotEmpty() }
            ?: throw AssertionError("응답에 $name 쿠키가 없다: ${response.headers().allValues("set-cookie")}")

    /** 포트를 먼저 잡아 두고 그 번호로 띄운다. 띄운 뒤에 번호를 캐내려면 Boot 내부 타입을 알아야 한다 */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val EMAIL = "shared-session@test.local"
        const val PASSWORD = "hunter2-and-then-some"
        const val SIGNUP =
            """{"email":"$EMAIL","password":"$PASSWORD","display_name":"관객","consents":{"terms_of_service":true,"privacy_collect":true}}"""
        const val LOGIN = """{"email":"$EMAIL","password":"$PASSWORD"}"""
    }
}
