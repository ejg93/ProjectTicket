package com.projectticket.ticket.auth

import com.projectticket.ticket.audit.AuditLog
import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler
import org.springframework.security.web.authentication.logout.LogoutHandler
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val signupService: SignupService,
    private val authenticationManager: AuthenticationManager,
    private val sessionAuthenticationStrategy: SessionAuthenticationStrategy,
    private val securityContextRepository: SecurityContextRepository,
    private val auditLog: AuditLog,
    private val loginAttempts: LoginAttemptService,
    logoutHandlers: List<LogoutHandler>,
) {

    /** 컨텍스트 비우기·세션 무효화·CSRF 쿠키 삭제. 구성은 [SecurityConfig] 가 빈으로 든다 */
    private val logoutHandler = CompositeLogoutHandler(logoutHandlers)

    @PostMapping("/signup")
    fun signUp(@Valid @RequestBody request: SignupRequest, http: HttpServletRequest): ResponseEntity<SignupResponse> {
        val accountId = signupService.signUp(
            SignupService.Command(
                email = request.email,
                password = request.password,
                displayName = request.displayName,
                consents = request.consents,
                actorIp = http.remoteAddr,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(SignupResponse(accountId))
    }

    /**
     * formLogin 이 없어서 필터가 하던 일을 여기서 손으로 한다 — 인증, 세션 교체·등록, 컨텍스트 저장.
     * 마지막 것을 빠뜨리면 이 요청은 성공하는데 다음 요청이 401 이다.
     */
    @PostMapping("/login")
    fun logIn(@Valid @RequestBody request: LoginRequest, http: HttpServletRequest, response: HttpServletResponse): LoginResponse {
        // **대조 앞에서 막는다**(`3a`). 뒤에서 보면 막힌 계정에도 bcrypt 를 계속 돌려서, 그 비용이 곧 공격자의 도구가 된다.
        // 시간 차가 새지 않는 이유는 막힌 사람이 **자기가 두드려서** 막힌 쪽이라 이미 아는 사실이어서다.
        if (loginAttempts.isBlocked(request.email, http.remoteAddr)) {
            recordLoginFailure(http, "blocked")
            throw TicketException(ErrorCode.LOGIN_FAILED)
        }

        val authentication = try {
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email, request.password),
            )
        } catch (e: AuthenticationException) {
            // 없는 계정·틀린 비밀번호가 같은 문구로 나간다(`D9`). 가르면 계정 존재를 흘린다.
            loginAttempts.recordFailure(request.email, http.remoteAddr)
            recordLoginFailure(http, "bad_credentials")
            throw TicketException(ErrorCode.LOGIN_FAILED)
        }

        val user = authentication.principal as TicketUser
        // 정지된 계정도 같은 문구다. 비밀번호 대조 뒤에 보는 이유는 TicketUser.isEnabled 에 있다 — 앞에서 보면 시간 차가 샌다.
        if (!user.active) {
            // **여기는 계정이 밝혀졌다.** 아래 「이메일을 안 담는다」는 누구인지 모르는 실패에 걸리는 말이고,
            // 아는 계정의 실패를 익명으로 남기면 「이 계정에 시도가 몇 번 왔나」에 답할 수 없다.
            loginAttempts.recordFailure(request.email, http.remoteAddr)
            recordLoginFailure(http, "suspended", user.id)
            throw TicketException(ErrorCode.LOGIN_FAILED)
        }

        // 맞는 비밀번호를 댔다. 다음 사람을 위해 카운터를 지운다 — 안 지우면 오타 넷을 낸 사람이 다음 실수에 막힌다.
        loginAttempts.reset(request.email, http.remoteAddr)

        // 로그인 앞에서 기존 세션을 버린다. `changeSessionId()` 는 생성 시각을 안 바꿔서 절대 만료(12h)가 익명 세션 시각부터 세게 된다.
        http.getSession(false)?.invalidate()
        http.getSession(true)

        sessionAuthenticationStrategy.onAuthentication(authentication, http, response)
        val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, http, response)

        auditLog.record(
            AuditLog.Kind.OUTCOME,
            "account.logged_in",
            user.id,
            AuditLog.Target.of("account", user.id),
            mapOf("ip" to http.remoteAddr),
        )
        return LoginResponse(user.id, user.username, user.role.code)
    }

    /**
     * 실패한 시도를 남긴다. **`ATTEMPT` 라야 한다** — 이 요청은 예외로 끝나므로 같은 트랜잭션에 담으면 롤백과 함께 사라지고,
     * 정작 남겨야 할 것이 실패한 시도다.
     *
     * **이메일을 안 담는다**(`D10`). 감사 로그는 파기 예외라 오래 남는데, 가입도 안 한 사람의 이메일이 3년을 남을 이유가 없다.
     * 누가 몇 번 틀렸나는 청크 `3a` 의 카운터가 Redis 에서 센다.
     */
    private fun recordLoginFailure(http: HttpServletRequest, reason: String, accountId: Long? = null) =
        auditLog.record(
            AuditLog.Kind.ATTEMPT,
            "account.login_failed",
            actorAccountId = accountId,
            detail = mapOf("ip" to http.remoteAddr, "reason" to reason),
        )

    @PostMapping("/logout")
    fun logOut(http: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Void> {
        logoutHandler.logout(http, response, SecurityContextHolder.getContext().authentication)
        return ResponseEntity.noContent().build()
    }

    data class SignupRequest(
        @field:NotBlank @field:EmailAddress val email: String,
        @field:NotBlank @field:Password val password: String,
        @field:NotBlank @field:Size(max = 50) val displayName: String,
        @field:NotNull val consents: Map<String, Boolean?>,
    ) {
        /** 비밀번호를 로그·디버거에 안 찍는다(`D10`). data class 기본 toString 이 평문을 낸다 */
        override fun toString(): String = "SignupRequest[email=$email, displayName=$displayName]"
    }

    data class SignupResponse(val accountId: Long)

    data class LoginRequest(@field:NotBlank val email: String, @field:NotBlank val password: String) {
        override fun toString(): String = "LoginRequest[email=$email]"
    }

    data class LoginResponse(val accountId: Long, val email: String, val role: String)
}
