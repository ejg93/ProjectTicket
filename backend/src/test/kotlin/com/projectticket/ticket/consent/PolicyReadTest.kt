package com.projectticket.ticket.consent

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper

/**
 * 개인정보처리방침이 **로그인 없이** 읽히고 제30조 제1항의 절을 다 드나(`39-3a`).
 *
 * 절 아홉은 `V21` 시드가 적은 것이다 — 시드를 줄이거나 목록 꼴로 바꾸면 화면(`ConsentBody`)이 못 그리는 것보다 먼저 여기서 빨개진다.
 */
class PolicyReadTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @Test
    fun anyone_reads_the_privacy_policy_with_every_section() {
        val body = mvc.get("/api/policies/privacy_policy").andExpect {
            status { isOk() }
            jsonPath("$.title") { value("개인정보처리방침") }
            jsonPath("$.version") { value(1) }
            jsonPath("$.effective_at") { exists() }
        }.andReturn().response.contentAsString.let { json.readTree(it)["body"].asString() }

        assertThat(Regex("(?m)^## ").findAll(body).count()).isEqualTo(SECTIONS)
        // 공개 저장소라 가상 값이다 — 예시라는 것이 본문 첫 문단에 있어야 한다(사용자 결정, 39-3).
        assertThat(body.trim().lineSequence().first()).contains("예시")
    }

    @Test
    fun an_unknown_code_is_not_found() {
        mvc.get("/api/policies/no-such-policy").andExpect {
            status { isNotFound() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:policy-not-found") }
        }
    }

    private companion object {
        /** 제30조 제1항에서 이 프로젝트가 적는 절 — 목적 · 항목·보유 · 제3자 · 위탁 · 파기 · 권리 · 안전성 · 자동 수집 · 보호책임자 */
        const val SECTIONS = 9
    }
}
