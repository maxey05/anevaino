package ph.anevaino.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.session.web.http.CookieSerializer
import org.springframework.session.web.http.DefaultCookieSerializer
import ph.anevaino.common.error.ProblemType
import ph.anevaino.common.ratelimit.TokenBucketRateLimiter
import java.time.Duration

@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val csrfRequestHandler = CsrfTokenRequestAttributeHandler()
        csrfRequestHandler.setCsrfRequestAttributeName(null)
        http
            .csrf { csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(csrfRequestHandler)
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .authorizeHttpRequests { registry ->
                registry
                    .requestMatchers(HttpMethod.GET, "/api/health", "/api/auth/csrf").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/google").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { handling ->
                handling
                    .authenticationEntryPoint(ProblemAuthenticationEntryPoint())
                    .accessDeniedHandler(ProblemAccessDeniedHandler())
            }
            .headers { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
        return http.build()
    }

    @Bean
    fun cookieSerializer(
        @Value("\${anevaino.session.secure-cookie:true}") secureCookie: Boolean,
    ): CookieSerializer =
        DefaultCookieSerializer().apply {
            setCookieName("SESSION")
            setCookiePath("/")
            setUseHttpOnlyCookie(true)
            setUseSecureCookie(secureCookie)
            setSameSite("Lax")
        }

    @Bean
    fun loginRateLimiter(
        @Value("\${anevaino.auth.login-rate-limit-per-minute:10}") requestsPerMinute: Int,
    ): TokenBucketRateLimiter = TokenBucketRateLimiter(requestsPerMinute, Duration.ofMinutes(1))
}

private fun writeProblem(response: HttpServletResponse, type: ProblemType, detail: String) {
    response.status = type.status.value()
    response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    response.characterEncoding = "UTF-8"
    response.writer.write(
        """{"type":"${type.slug}","title":"${type.title}","status":${type.status.value()},""" +
            """"detail":"$detail","requestId":"${MDC.get("requestId") ?: ""}"}""",
    )
}

class ProblemAuthenticationEntryPoint : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        writeProblem(response, ProblemType.AUTH_UNAUTHENTICATED, "You need to sign in first.")
    }
}

class ProblemAccessDeniedHandler : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        writeProblem(response, ProblemType.AUTH_FORBIDDEN, "This request was rejected. Please reload and try again.")
    }
}