package ph.anevaino.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ph.anevaino.common.error.InvalidToken
import ph.anevaino.common.error.NonDlsuAccount
import ph.anevaino.common.error.RateLimited
import ph.anevaino.common.ratelimit.TokenBucketRateLimiter
import ph.anevaino.user.MeDto
import ph.anevaino.user.UserService

const val NON_DLSU_DETAIL =
    "Anevaino is only for De La Salle University accounts. " +
        "Please sign in with your @dlsu.edu.ph Google account."

data class GoogleLoginRequest(val credential: String = "")

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val googleIdTokenVerifier: GoogleIdTokenVerifier,
    private val userService: UserService,
    private val sessionAuthenticator: SessionAuthenticator,
    private val loginRateLimiter: TokenBucketRateLimiter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/csrf")
    fun csrf(csrfToken: CsrfToken): ResponseEntity<Void> {
        csrfToken.token
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/google")
    fun google(
        @RequestBody body: GoogleLoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): MeDto {
        val decision = loginRateLimiter.tryConsume(clientIp(request))
        if (!decision.allowed) {
            throw RateLimited("Too many sign-in attempts. Please try again shortly.", decision.retryAfterSeconds)
        }
        val claims =
            try {
                googleIdTokenVerifier.verify(body.credential)
            } catch (ex: InvalidToken) {
                log.warn("login.rejected reason=INVALID_TOKEN")
                throw ex
            }
        if (!DlsuDomainPolicy.isAllowed(claims)) {
            log.warn("login.rejected reason=NON_DLSU")
            throw NonDlsuAccount(NON_DLSU_DETAIL)
        }
        val user = userService.findOrCreate(claims.email, claims.name)
        sessionAuthenticator.startSession(user.id, request, response)
        log.info("login.success userId={}", user.id)
        return userService.toMeDto(user)
    }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<Void> {
        sessionAuthenticator.endSession(request)
        return ResponseEntity.noContent().build()
    }

    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr
}