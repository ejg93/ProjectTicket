package com.projectticket.ticket.auth

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post

/**
 * 흔한 비밀번호를 거절하나(`3b` 의 닫힘 조건, NIST SP 800-63B §3.1.1.2 의 SHALL).
 *
 * **길이 규칙이 이미 대부분을 막는다** — 최소 15자라 `password123` 은 애초에 못 들어온다.
 * 그래서 여기서 재는 것은 **길이를 통과하는 흔한 것**이다: 늘려 만든 것, 연속 문자, 서비스·계정 이름.
 *
 * 강제 지점이 앱 검증인 이유는 DB 가 해시만 들어서다 — 무엇이었는지 알 수가 없다(`D9`).
 */
class PasswordBlocklistTest : PostgresTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var blocklist: PasswordBlocklist

    @Test
    fun common_password_rejected() {
        // **길이를 통과하는 흔한 비밀번호**다 — 목록 단어를 두 번 썼을 뿐이라 사전 단어 하나와 다를 것이 없다.
        signUp("passwordpassword").andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("tag:projectticket.example,2026:validation-failed") }
            jsonPath("$.errors[0].field") { value("password") }
        }
    }

    @Test
    fun the_list_itself_is_checked() {
        // 목록 10,001개 중 **15자를 넘는 것은 하나뿐**이다 — 길이 규칙(`D9`)이 이미 나머지를 막는다.
        // 그래도 대조는 남긴다: 길이 정책이 바뀌는 날 이 규칙만 남는다.
        assertThat(blocklist.rejection("films+pic+galeries")).isNotNull()
    }

    @Test
    fun a_common_word_repeated_is_still_common() {
        // 「사전 단어를 두 번 썼을 뿐」이다. 길이 규칙만 있으면 이것이 통과한다.
        assertThat(blocklist.rejection("passwordpassword")).isNotNull()
        assertThat(blocklist.rejection("dragondragondragon")).isNotNull()
    }

    @Test
    fun repetitive_and_sequential_are_rejected() {
        assertThat(blocklist.rejection("aaaaaaaaaaaaaaaa")).isNotNull()
        assertThat(blocklist.rejection("123456789012345")).isNotNull()
        assertThat(blocklist.rejection("abcdefghijklmnop")).isNotNull()
    }

    @Test
    fun service_words_are_context_specific() {
        // SHALL 이 드는 「context-specific words」다. 우리만 아는 말이라 목록이 아니라 코드에 있다.
        assertThat(blocklist.rejection("projectticket2026")).isNotNull()
        assertThat(blocklist.rejection("my-ticket-password")).isNotNull()
    }

    @Test
    fun a_password_made_from_the_email_is_rejected() {
        // 형식 제약은 비밀번호만 본다. 이메일과 같이 보는 자리는 가입 서비스뿐이다.
        signUp(password = "goodenoughlong-1", email = "goodenoughlong@test.local").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("password") }
        }
    }

    @Test
    fun an_ordinary_long_password_passes() {
        // 막는 것이 목적이 아니라 **흔한 것**을 막는 것이 목적이다. 평범한 긴 비밀번호는 지나가야 한다.
        signUp("자갈치-시장-갈매기-일곱").andExpect { status { isBadRequest() } } // ASCII 제약(`D9`)에 걸린다
        signUp("gravel-market-gull-7").andExpect { status { isCreated() } }
    }

    private fun signUp(password: String, email: String = "blocklist@test.local"): ResultActionsDsl =
        mvc.post("/api/auth/signup") {
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","display_name":"gwangaek","consents":{"terms_of_service":true,"privacy_collect":true}}"""
        }
}
