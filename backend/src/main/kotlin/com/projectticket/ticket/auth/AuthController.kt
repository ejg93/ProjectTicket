package com.projectticket.ticket.auth

import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
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
    logoutHandlers: List<LogoutHandler>,
) {

    /** 컨텍스트 비우기·세션 무효화·CSRF 쿠키 삭제. 구성은 [SecurityConfig] 가 빈으로 든다 */
    private val logoutHandler = CompositeLogoutHandler(logoutHandlers)

    @PostMapping("/signup")
    fun signUp(@Valid @RequestBody request: SignupRequest): ResponseEntity<SignupResponse> {
        val accountId = signupService.signUp(SignupService.Command(request.email, request.password, request.displayName))
        return ResponseEntity.status(HttpStatus.CREATED).body(SignupResponse(accountId))
    }

    /**
     * formLogin 이 없어서 필터가 하던 일을 여기서 손으로 한다 — 인증, 세션 교체·등록, 컨텍스트 저장.
     * 마지막 것을 빠뜨리면 이 요청은 성공하는데 다음 요청이 401 이다.
     */
    @PostMapping("/login")
    fun logIn(@Valid @RequestBody request: LoginRequest, http: HttpServletRequest, response: HttpServletResponse): LoginResponse {
        val authentication = try {
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email, request.password),
            )
        } catch (e: AuthenticationException) {
            // 없는 계정·틀린 비밀번호가 같은 문구로 나간다(`D9`). 가르면 계정 존재를 흘린다.
            throw TicketException(ErrorCode.LOGIN_FAILED)
        }

        val user = authentication.principal as TicketUser
        // 정지된 계정도 같은 문구다. 비밀번호 대조 뒤에 보는 이유는 TicketUser.isEnabled 에 있다 — 앞에서 보면 시간 차가 샌다.
        if (!user.active) {
            throw TicketException(ErrorCode.LOGIN_FAILED)
        }

        // 로그인 앞에서 기존 세션을 버린다. `changeSessionId()` 는 생성 시각을 안 바꿔서 절대 만료(12h)가 익명 세션 시각부터 세게 된다.
        http.getSession(false)?.invalidate()
        http.getSession(true)

        sessionAuthenticationStrategy.onAuthentication(authentication, http, response)
        val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, http, response)

        return LoginResponse(user.id, user.username, user.role.code)
    }

    @PostMapping("/logout")
    fun logOut(http: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Void> {
        logoutHandler.logout(http, response, SecurityContextHolder.getContext().authentication)
        return ResponseEntity.noContent().build()
    }

    data class SignupRequest(
        @field:NotBlank @field:EmailAddress val email: String,
        @field:NotBlank @field:Password val password: String,
        @field:NotBlank @field:Size(max = 50) val displayName: String,
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
