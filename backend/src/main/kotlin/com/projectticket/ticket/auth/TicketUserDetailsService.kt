package com.projectticket.ticket.auth

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.CredentialsContainer
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

/**
 * 로그인할 때 계정을 DB 에서 찾는다.
 *
 * `deleted_at is null` 을 조건에 넣는다. 탈퇴부터 파기까지는 이메일이 살아 있어서 이 조건이 없으면 탈퇴 계정이 로그인된다.
 * 이 조건은 로그인 시점만 막는다. 이미 로그인한 다른 기기는 [AccountLivenessFilter] 가 막는다.
 */
@Service
class TicketUserDetailsService(private val jdbc: JdbcClient) : UserDetailsService {

    override fun loadUserByUsername(email: String): UserDetails =
        jdbc.sql(
            """
            select account_id, email, password_hash, role, status
              from account
             where lower(email) = lower(:email)
               and deleted_at is null
            """,
        )
            .param("email", email)
            .query { rs, _ ->
                TicketUser(
                    id = rs.getLong("account_id"),
                    email = rs.getString("email"),
                    role = AccountRole.of(rs.getString("role")),
                    passwordHash = rs.getString("password_hash"),
                    active = AccountStatus.of(rs.getString("status")) == AccountStatus.ACTIVE,
                )
            }
            .optional()
            // 없는 계정과 틀린 비밀번호가 같은 결과로 나가야 한다(`D9`). 컨트롤러가 하나의 문구로 뭉친다.
            .orElseThrow { UsernameNotFoundException("계정이 없다") }

    /**
     * 세션에 principal 로 앉는 객체.
     *
     * `data class` 가 아니라 클래스인 이유는 비밀번호 해시를 지워야 해서다. 인증이 끝나면 `ProviderManager` 가
     * [eraseCredentials] 를 부르고, 불변 객체는 그 요청에 응답할 방법이 없어서 해시가 세션이 사는 내내 메모리에 남는다(`D9`).
     *
     * `equals` 를 id 로만 본다. 해시까지 비교하면 지워진 뒤 `SessionRegistry` 에서 같은 사람을 못 찾는다 — 탈퇴가 세션을 못 끊는다.
     */
    class TicketUser(
        val id: Long,
        private val email: String,
        val role: AccountRole,
        passwordHash: String?,
        active: Boolean,
    ) : UserDetails, CredentialsContainer {

        private val activeFlag: Boolean = active

        /** 인증이 끝나면 지워진다. 그 뒤로 이 값을 읽는 코드가 있으면 안 된다 */
        private var passwordHash: String? = passwordHash

        override fun eraseCredentials() {
            passwordHash = null
        }

        override fun getAuthorities(): Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority(role.authority))

        override fun getPassword(): String? = passwordHash

        override fun getUsername(): String = email

        /**
         * 항상 true 다. `DaoAuthenticationProvider` 는 이 값을 비밀번호 대조보다 먼저 봐서 false 면 bcrypt 없이 즉시 거절한다 —
         * 정지된 계정은 응답이 수십 ms 짧아지고 그 시간 차가 「이 이메일은 존재한다」를 흘린다(`D9`).
         * 정지 판정은 비밀번호 대조 뒤에 [AuthController] 가 [active] 로 하고, 문구는 같다.
         */
        override fun isEnabled(): Boolean = true

        /** 업무 상태가 활성인가. 로그인 컨트롤러가 인증 뒤에 본다 */
        val active: Boolean get() = activeFlag

        override fun equals(other: Any?): Boolean = other is TicketUser && other.id == id

        override fun hashCode(): Int = id.hashCode()

        /** 해시는 안 넣는다. 로그·디버거에 찍히는 자리다(`D10`) */
        override fun toString(): String = "TicketUser[id=$id, role=$role]"
    }
}
