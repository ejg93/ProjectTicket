package com.projectticket.ticket.error

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 프레임워크가 만든 오류도 우리 `type` 을 단다(`D5`). 상태 코드보다 `type` 이 더 뭉치면 프론트의 분기가 뒤집힌다.
 */
class ProblemDetailTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun broken_json_is_malformed_request() {
        mvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = "{not json"
            with(csrf())
        }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("tag:projectticket.example,2026:malformed-request") }
            jsonPath("$.instance") { value("/api/auth/login") }
        }
    }

    /** 경로 변수의 형이 틀려도 입력을 되돌려 싣지 않는다(`D9` 「입력과 출력」, `I13-4`). 프레임워크 기본 `detail` 은 값을 그대로 적는다 */
    @Test
    fun a_path_type_mismatch_does_not_echo_the_input() {
        // `instance` 는 요청 URI 라 그 값이 실린다(RFC 9457 §3.1.5). 되돌려 싣지 말 것은 `detail` 이다.
        mvc.get("/api/events/zq9echo").andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:malformed-request") }
            jsonPath("$.detail") { value(Matchers.not(Matchers.containsString("zq9echo"))) }
        }
    }

    /** 쿼리의 형이 틀려도 같다. 바인딩 실패 문구는 입력과 Java 타입 이름을 같이 적는다 */
    @Test
    fun a_query_type_mismatch_does_not_echo_the_input() {
        val body = mvc.get("/api/events?page=zq9echo")
            .andExpect { status { isBadRequest() } }
            .andReturn()
            .response
            .contentAsString

        assertThat(body).doesNotContain("zq9echo").doesNotContain("java.lang")
    }

    @Test
    fun wrong_media_type_keeps_its_own_type() {
        mvc.post("/api/auth/login") {
            contentType = MediaType.TEXT_PLAIN
            content = "email=a"
            with(csrf())
        }.andExpect {
            status { isUnsupportedMediaType() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:unsupported-media-type") }
        }
    }

    /**
     * 없는 경로는 404 가 아니라 401 이다. 인가 필터가 MVC 보다 앞이라 로그인 없이는 경로의 존재를 못 알아낸다 —
     * 404 를 주면 어떤 경로가 있는지를 비로그인에게 알려 주는 것이다(`D9`). `ENDPOINT_NOT_FOUND` 는 로그인한 뒤에만 나간다.
     */
    @Test
    fun unknown_path_is_unauthenticated_not_found() {
        mvc.get("/api/health/nope").andExpect {
            status { isUnauthorized() }
        }
        mvc.post("/api/auth/signup/nope") { with(csrf()) }.andExpect {
            status { isUnauthorized() }
        }
    }
}
