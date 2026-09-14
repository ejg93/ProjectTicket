package com.projectticket.ticket.auth

import com.projectticket.ticket.error.ProblemEntryPoint
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.function.Supplier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.core.session.SessionRegistryImpl
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextHolderFilter
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.csrf.CsrfTokenRequestHandler
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler
import org.springframework.security.web.session.ConcurrentSessionFilter
import org.springframework.security.web.session.HttpSessionEventPublisher
import org.springframework.util.StringUtils
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 세션 기반 인증(`D9`, ADR 0001). JWT 는 대기열 토큰(청크 22)에서 따로 배운다.
 *
 * - formLogin·httpBasic 을 끈다. 둘 다 302 나 브라우저 팝업으로 답해서 JSON API 와 안 맞는다. 로그인은 [AuthController] 가 한다
 * - 인증 실패는 [ProblemEntryPoint] 가 RFC 9457 로 답한다
 * - 로그인할 때 세션 ID 를 갈아 끼운다(세션 고정 방어)
 * - CSRF 토큰을 쿠키(`XSRF-TOKEN`, httpOnly 아님)로 내리고 헤더(`X-XSRF-TOKEN`)로 받는다. SPA 가 쿠키를 읽어 헤더에 싣는다
 */
@Configuration
class SecurityConfig {

    @Bean
    fun filterChain(
        http: HttpSecurity,
        jdbc: JdbcClient,
        sessionRegistry: SessionRegistry,
        entryPoint: ProblemEntryPoint,
    ): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(*PUBLIC_PATHS.toTypedArray()).permitAll()
                    .anyRequest().authenticated()
            }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .exceptionHandling { it.authenticationEntryPoint(entryPoint) }
            .sessionManagement { session ->
                session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation { it.changeSessionId() }
            }
            .csrf { csrf ->
                csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(csrfTokenRequestHandler())
            }
            .addFilterAfter(CsrfCookieFilter(), CsrfFilter::class.java)
            .addFilterBefore(AccountLivenessFilter(jdbc), AuthorizationFilter::class.java)
            // 탈퇴·정지가 레지스트리의 세션을 만료시키면 그 세션의 다음 요청을 401 로 끊는다.
            .addFilterAfter(
                ConcurrentSessionFilter(sessionRegistry) { event ->
                    event.response.status = HttpStatus.UNAUTHORIZED.value()
                },
                SecurityContextHolderFilter::class.java,
            )
            .addFilterAfter(AbsoluteSessionTimeoutFilter(), SecurityContextHolderFilter::class.java)
        return http.build()
    }

    /**
     * 토큰을 실제로 읽어야 쿠키가 내려간다. 지연 로딩이라 아무도 안 읽으면 `Set-Cookie` 가 안 나가고,
     * 그러면 SPA 의 첫 POST 가 토큰 없이 403 을 받는다.
     */
    private class CsrfCookieFilter : OncePerRequestFilter() {
        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
            (request.getAttribute(CsrfToken::class.java.name) as CsrfToken?)?.token
            chain.doFilter(request, response)
        }
    }

    /**
     * 헤더로 오면 평문, 파라미터로 오면 XOR 인코딩으로 푼다(Spring Security 참조 문서의 SPA 권장 구성).
     * 헤더 값은 JS 가 쿠키에서 그대로 옮기므로 인코딩이 없다.
     */
    private fun csrfTokenRequestHandler(): CsrfTokenRequestHandler =
        object : CsrfTokenRequestAttributeHandler() {
            private val xor = XorCsrfTokenRequestAttributeHandler()

            override fun handle(request: HttpServletRequest, response: HttpServletResponse, csrfToken: Supplier<CsrfToken>) =
                xor.handle(request, response, csrfToken)

            override fun resolveCsrfTokenValue(request: HttpServletRequest, csrfToken: CsrfToken): String? =
                if (StringUtils.hasText(request.getHeader(csrfToken.headerName))) {
                    super.resolveCsrfTokenValue(request, csrfToken)
                } else {
                    xor.resolveCsrfTokenValue(request, csrfToken)
                }
        }

    /** `{bcrypt}` 접두가 붙는 위임 인코더. 알고리즘을 바꿔도 저장된 해시가 그대로 검증된다 */
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun authenticationManager(userDetailsService: UserDetailsService, passwordEncoder: PasswordEncoder): AuthenticationManager =
        ProviderManager(DaoAuthenticationProvider(userDetailsService).apply { setPasswordEncoder(passwordEncoder) })

    /** 로그인 성공 시 세션 ID 를 바꾸고 레지스트리에 등록한다. 둘 다 [AuthController] 가 손으로 부른다 — formLogin 이 없어서다 */
    @Bean
    fun sessionAuthenticationStrategy(sessionRegistry: SessionRegistry): SessionAuthenticationStrategy =
        CompositeSessionAuthenticationStrategy(
            listOf(ChangeSessionIdAuthenticationStrategy(), RegisterSessionAuthenticationStrategy(sessionRegistry)),
        )

    @Bean
    fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()

    @Bean
    fun sessionRegistry(): SessionRegistry = SessionRegistryImpl()

    /** 세션이 죽을 때 레지스트리에서도 빠지게 한다. 없으면 레지스트리가 유령 세션을 든다 */
    @Bean
    fun httpSessionEventPublisher(): HttpSessionEventPublisher = HttpSessionEventPublisher()

    companion object {
        /** 로그인 없이 되는 경로. 공연 목록·좌석 현황 조회는 그 청크(10)가 여기에 더한다 */
        val PUBLIC_PATHS = listOf(
            "/api/health",
            "/actuator/health",
            "/actuator/health/**",
            "/api/auth/signup",
            "/api/auth/login",
        )
    }
}
