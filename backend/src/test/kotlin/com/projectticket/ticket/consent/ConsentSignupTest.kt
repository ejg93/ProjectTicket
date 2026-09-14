package com.projectticket.ticket.consent

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 가입이 동의 없이 지나가지 않는가, 그리고 받은 동의가 사건으로 남는가.
 *
 * 항목이 데이터라 목록은 화면이 물어서 안다(`GET /api/consent-items`). 코드에 목록을 박으면 항목을 데이터로 둔 뜻이 없어진다.
 */
class ConsentSignupTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun items_are_public_and_ordered() {
        // 가입 화면은 로그인 전에 이 목록을 본다.
        mvc.get("/api/consent-items").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(3) }
            jsonPath("$[0].code") { value("terms_of_service") }
            jsonPath("$[0].required") { value(true) }
            jsonPath("$[2].code") { value("marketing_email") }
            jsonPath("$[2].required") { value(false) }
        }
    }

    @Test
    fun signup_without_required_consent_is_rejected() {
        signUp(mapOf("terms_of_service" to true)).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:required-consent-missing") }
        }
        assertThat(accountCount()).describedAs("동의가 빠졌는데 계정이 생기면 안 된다").isZero()
    }

    @Test
    fun refusing_required_consent_is_rejected() {
        signUp(mapOf("terms_of_service" to true, "privacy_collect" to false)).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:required-consent-missing") }
        }
    }

    @Test
    fun unknown_item_is_rejected() {
        signUp(mapOf("terms_of_service" to true, "privacy_collect" to true, "nope" to true)).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:unknown-consent-item") }
        }
    }

    @Test
    fun untouched_optional_item_leaves_no_row() {
        signUp(mapOf("terms_of_service" to true, "privacy_collect" to true)).andExpect { status { isCreated() } }

        // 거부(false 행)와 무응답(행 없음)이 갈려야 한다. 안 갈리면 나중에 「물어본 적 있나」에 답을 못 한다.
        assertThat(consentCodes()).containsExactlyInAnyOrder("terms_of_service", "privacy_collect")
    }

    @Test
    fun refused_optional_item_leaves_a_false_row() {
        signUp(mapOf("terms_of_service" to true, "privacy_collect" to true, "marketing_email" to false))
            .andExpect { status { isCreated() } }

        assertThat(consentCodes()).contains("marketing_email")
        assertThat(
            jdbc.sql("select granted from current_consent where item_code = 'marketing_email'")
                .query(Boolean::class.java).single(),
        ).isFalse()
    }

    @Test
    fun null_consent_value_is_rejected_not_crashed() {
        // 타입이 Boolean 이어도 JSON 의 null 은 들어온다. 그대로 두면 not null 컬럼에 부딪쳐 500 이 되고,
        // 사용자는 무엇을 고쳐야 하는지 못 듣는다.
        signUpRaw("""{"terms_of_service":true,"privacy_collect":true,"marketing_email":null}""").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.detail") { value("동의 여부가 비어 있다: marketing_email") }
        }
    }

    @Test
    fun signup_records_an_audit_row() {
        signUp(mapOf("terms_of_service" to true, "privacy_collect" to true)).andExpect { status { isCreated() } }

        assertThat(
            jdbc.sql("select count(*) from audit_log where event_type = 'account.signed_up'")
                .query(Long::class.java).single(),
        ).isEqualTo(1)
    }

    private fun signUpRaw(consentsJson: String) = mvc.post("/api/auth/signup") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"email":"consent@test.local","password":"hunter2-and-then-some","display_name":"관객","consents":$consentsJson}"""
        with(csrf())
    }

    private fun signUp(consents: Map<String, Boolean>) = mvc.post("/api/auth/signup") {
        contentType = MediaType.APPLICATION_JSON
        content = """
            {"email":"consent@test.local","password":"hunter2-and-then-some",
             "display_name":"관객","consents":${consents.entries.joinToString(",", "{", "}") { """"${it.key}":${it.value}""" }}}
        """.trimIndent()
        with(csrf())
    }

    private fun accountCount(): Long =
        jdbc.sql("select count(*) from account where email = 'consent@test.local'").query(Long::class.java).single()

    private fun consentCodes(): List<String> =
        jdbc.sql("select item_code from current_consent").query(String::class.java).list().filterNotNull()
}
