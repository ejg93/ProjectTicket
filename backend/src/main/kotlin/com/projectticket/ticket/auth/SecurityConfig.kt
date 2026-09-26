package com.projectticket.ticket.auth

import com.projectticket.ticket.error.ProblemAccessDeniedHandler
import com.projectticket.ticket.error.ProblemEntryPoint
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.function.Supplier
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.csrf.CsrfLogoutHandler
import org.springframework.security.web.authentication.logout.LogoutHandler
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextHolderFilter
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.csrf.CsrfTokenRequestHandler
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler
import org.springframework.security.web.session.ConcurrentSessionFilter
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.session.security.SpringSessionBackedSessionRegistry
import org.springframework.session.web.http.CookieSerializer
import org.springframework.session.web.http.DefaultCookieSerializer
import org.springframework.util.StringUtils
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 세션 기반 인증(`D9`, ADR 0001). JWT 는 대기열 토큰(청크 22)에서 따로 배운다.
 *
 * - formLogin·httpBasic·기본 logout 을 끈다. 셋 다 302 나 브라우저 팝업으로 답해서 JSON API 와 안 맞는다. 로그인·로그아웃은 [AuthController] 가 한다
 * - 인증 없음(401)은 [ProblemEntryPoint], 권한 없음·CSRF 실패(403)는 [ProblemAccessDeniedHandler] 가 RFC 9457 로 답한다
 * - 세션 고정 방어는 [sessionAuthenticationStrategy] 한 곳이다. `sessionManagement.sessionFixation` 은 인증 필터가 부르는 것이라
 *   formLogin 이 없는 여기서는 죽은 설정이다 — 두 곳에 두면 한쪽이 안 도는데 아무도 모른다
 * - CSRF 토큰을 쿠키(`XSRF-TOKEN`, httpOnly 아님)로 내리고 헤더(`X-XSRF-TOKEN`)로 받는다. SPA 가 쿠키를 읽어 헤더에 싣는다
 * - 세션은 톰캣 메모리가 아니라 Redis 에 산다(ADR 0004, `20a`). 저장소만 바뀌고 쿠키·CSRF·로그아웃 계약은 그대로다
 */
@Configuration
class SecurityConfig {

    @Bean
    // 필터 체인 하나가 보안 구성 전부를 든다 — 빈 일곱은 Spring 이 넣고, 가르면 체인이 둘이 된다(`G8`).
    @Suppress("LongParameterList")
    fun filterChain(
        http: HttpSecurity,
        jdbc: JdbcClient,
        sessionRegistry: SessionRegistry,
        entryPoint: ProblemEntryPoint,
        accessDeniedHandler: ProblemAccessDeniedHandler,
        csrfTokenRepository: CsrfTokenRepository,
        securedFilters: List<SecuredApiFilter>,
    ): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(HttpMethod.GET, *PUBLIC_GET_PATHS.toTypedArray()).permitAll()
                    .requestMatchers(HttpMethod.POST, *PUBLIC_POST_PATHS.toTypedArray()).permitAll()
                    // 경로 접두로 역할을 가른다(`D5`). **필터라 컨트롤러 밖이다** — 기획사 입구가 늘어도 빠뜨릴 자리가 없다.
                    .requestMatchers(ORGANIZER_PREFIX).hasAuthority(AccountRole.ORGANIZER.authority)
                    .requestMatchers(ADMIN_PREFIX).hasAuthority(AccountRole.ADMIN.authority)
                    .anyRequest().authenticated()
            }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .exceptionHandling {
                it.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler)
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .csrf { csrf ->
                csrf
                    .csrfTokenRepository(csrfTokenRepository)
                    .csrfTokenRequestHandler(csrfTokenRequestHandler())
            }
            .addFilterAfter(CsrfCookieFilter(), CsrfFilter::class.java)
            .addFilterBefore(AccountLivenessFilter(jdbc, entryPoint), AuthorizationFilter::class.java)
            // 레지스트리에서 만료된 세션(탈퇴·관리자 정지가 `SessionInformation.expireNow()` 를 부르는 청크가 온다)의 다음 요청을 401 로 끊는다.
            // 지금은 부르는 곳이 없어서 [AccountLivenessFilter] 가 같은 일을 DB 조회로 한다 — 이 필터는 그때를 위한 자리다.
            .addFilterAfter(
                ConcurrentSessionFilter(sessionRegistry) { event ->
                    entryPoint.commence(event.request, event.response, InsufficientAuthenticationException("세션이 만료됐다"))
                },
                SecurityContextHolderFilter::class.java,
            )
            .addFilterAfter(AbsoluteSessionTimeoutFilter(), SecurityContextHolderFilter::class.java)
        // 인가 뒤에 도는 업무 필터들([SecuredApiFilter]) — 지금은 대기열 관문(23) 하나다.
        // 앞에 두면 로그인 안 한 사람이 401 대신 429 를 받는다: 줄을 서라는 말은 로그인한 사람에게만 뜻이 있다(`D12`).
        securedFilters.forEach { http.addFilterAfter(it, AuthorizationFilter::class.java) }
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

    /**
     * 세션 쿠키의 계약(이름·httpOnly·SameSite·secure)을 정하는 자리.
     *
     * Boot 의 자동설정에 맡기면 **배포 모양에 따라 값이 달라진다** — 서블릿 컨텍스트가 있으면 war 배포로 보고
     * 그쪽 `SessionCookieConfig` 를 읽는데, MockMvc 테스트가 바로 그 경우라 이름이 `SESSION` 으로 떨어졌다(`stack.md`).
     * 값은 `application.yml` 의 `server.servlet.session.cookie` 한 곳에서 읽는다 — 두 벌로 적지 않는다.
     */
    @Bean
    fun cookieSerializer(server: ServerProperties): CookieSerializer =
        DefaultCookieSerializer().apply {
            val cookie = server.servlet.session.cookie
            cookie.name?.let { setCookieName(it) }
            cookie.httpOnly?.let { setUseHttpOnlyCookie(it) }
            cookie.secure?.let { setUseSecureCookie(it) }
            cookie.sameSite?.let { setSameSite(it.attributeValue()) }
        }

    /** 빈으로 두는 이유는 로그인(토큰 회전)·로그아웃(쿠키 삭제)도 같은 저장소를 봐야 해서다 */
    @Bean
    fun csrfTokenRepository(): CsrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse()

    /** `{bcrypt}` 접두가 붙는 위임 인코더. 알고리즘을 바꿔도 저장된 해시가 그대로 검증된다 */
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun authenticationManager(userDetailsService: UserDetailsService, passwordEncoder: PasswordEncoder): AuthenticationManager =
        ProviderManager(DaoAuthenticationProvider(userDetailsService).apply { setPasswordEncoder(passwordEncoder) })

    /**
     * 로그인 성공 시 세션 ID 교체(세션 고정 방어)와 CSRF 토큰 회전. formLogin 이 없어서 [AuthController] 가 손으로 부른다.
     * 세션 고정 방어를 정하는 자리는 여기 하나다.
     *
     * 레지스트리 등록(`RegisterSessionAuthenticationStrategy`)은 없다. [sessionRegistry] 가 Redis 를 읽는 구현이라 등록이 빈 함수고,
     * 계정별 색인은 Spring Session 이 세션에 앉은 `SecurityContext` 를 보고 저장할 때 만든다.
     */
    @Bean
    fun sessionAuthenticationStrategy(csrfTokenRepository: CsrfTokenRepository): SessionAuthenticationStrategy =
        CompositeSessionAuthenticationStrategy(
            listOf(
                ChangeSessionIdAuthenticationStrategy(),
                CsrfAuthenticationStrategy(csrfTokenRepository),
            ),
        )

    /** 로그아웃이 하는 일. 기본 `LogoutFilter` 를 껐으므로 [AuthController] 가 이 목록을 순서대로 부른다 */
    @Bean
    fun logoutHandlers(csrfTokenRepository: CsrfTokenRepository): List<LogoutHandler> =
        listOf(SecurityContextLogoutHandler(), CsrfLogoutHandler(csrfTokenRepository))

    @Bean
    fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()

    /**
     * 세션을 Redis 에서 찾는 레지스트리(ADR 0004). 인스턴스가 셋이어도 같은 계정의 세션 전부를 본다 —
     * 정지·탈퇴(`5a`·`5b`)가 남의 인스턴스에 붙은 세션을 끊는 자리가 여기다.
     *
     * `HttpSessionEventPublisher` 를 안 둔다. 죽은 세션을 레지스트리에서 걷어내던 일이 저장소로 내려갔다 —
     * 목록이 Redis 의 색인이라 만료된 키는 조회에 안 잡힌다.
     *
     * `getAllPrincipals()` 는 이 구현이 못 한다(Redis 에 그 목록이 없다). 「지금 누가 접속해 있나」가 필요하면 세는 자리를 따로 만든다.
     */
    @Bean
    fun <S : Session> sessionRegistry(sessions: FindByIndexNameSessionRepository<S>): SessionRegistry =
        SpringSessionBackedSessionRegistry(sessions)

    companion object {
        /** 기획사 전용 접두. 여기서 막힌 403 은 `organizer-forbidden` 이다(`ProblemAccessDeniedHandler`) */
        const val ORGANIZER_PREFIX = "/api/organizer/**"

        /** 관리자 전용 접두(5b). 역할을 컨트롤러가 다시 안 센다 — 입구가 늘어도 빠뜨릴 자리가 없다 */
        const val ADMIN_PREFIX = "/api/admin/**"

        /**
         * 로그인 없이 읽는 경로. **메서드까지 가른다**(`S1`) — 경로만 열면 같은 경로의 DELETE·PUT 도 인증 없이 인가를 지난다.
         * 여기 없는 메서드는 익명이면 401 이다.
         */
        val PUBLIC_GET_PATHS = listOf(
            "/api/health",
            "/actuator/health",
            "/actuator/health/**",
            // 수집기가 읽는다(30). 로컬 compose 는 인증이 없어서 열어 두고, 밖에 나갈 때는 망으로 가른다(`D9`).
            "/actuator/prometheus",
            // 가입 화면이 무엇에 동의를 받아야 하는지 알아야 한다. 로그인 전에 보는 것이라 공개다.
            "/api/consent-items",
            // 약관·처리방침 본문(`39-1`). 발에서 누구나 닿아야 한다.
            "/api/consent-items/*",
            // 공연 목록·상세도 로그인 전에 본다 — 무엇을 파는지 보고 나서 로그인한다(`40a`).
            "/api/events",
            "/api/events/*",
            // 회차 하나(`40c`). `*` 는 한 단계라 `/api/performances/*/reservations` 는 안 열린다 — `PerformanceDetailTest` 가 본다.
            "/api/performances/*",
            // 좌석도는 로그인 전에 본다 — 자리를 보고 나서 로그인한다.
            "/api/performances/*/seats",
            // 델타도 같은 그림이다(10a). 로그인 전에 좌석을 보고 나서 로그인한다.
            "/api/performances/*/seats/changes",
            // 취소 수수료 구간표(`42-1a`). 결제 전에 보여 주는 공통 표다 — 예매 하나에 안 묶인다.
            "/api/refund-tiers",
            // 개인정보처리방침(`39-3a`, 제30조 제2항). 누구나 쉽게 확인할 수 있어야 한다.
            "/api/policies/*",
        )

        /** 로그인 없이 쓰는 경로 — 가입·로그인 둘. 로그아웃은 세션이 있어야 뜻이 있어 여기 없다 */
        val PUBLIC_POST_PATHS = listOf(
            "/api/auth/signup",
            "/api/auth/login",
        )
    }
}
