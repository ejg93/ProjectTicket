package com.projectticket.ticket.error

import com.projectticket.ticket.PostgresTestBase
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

    @Test
    fun unknown_public_endpoint_is_endpoint_not_found() {
        // 공개 경로 아래의 없는 자원이라 인증 필터가 아니라 MVC 가 답한다.
        mvc.get("/api/health/nope").andExpect {
            status { isUnauthorized() }
        }
        mvc.post("/api/auth/signup/nope") { with(csrf()) }.andExpect {
            status { isUnauthorized() }
        }
    }
}
