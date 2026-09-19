package ph.anevaino.common.error

import org.springframework.http.HttpStatus

enum class ProblemType(val slug: String, val status: HttpStatus, val title: String) {
    INTERNAL_ERROR("common/internal-error", HttpStatus.INTERNAL_SERVER_ERROR, "Internal error"),
    NOT_FOUND("common/not-found", HttpStatus.NOT_FOUND, "Not found"),
    VALIDATION("common/validation", HttpStatus.BAD_REQUEST, "Validation failed"),
    RATE_LIMITED("common/rate-limited", HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    AUTH_INVALID_TOKEN("auth/invalid-token", HttpStatus.BAD_REQUEST, "Invalid sign-in token"),
    AUTH_NON_DLSU("auth/non-dlsu-account", HttpStatus.FORBIDDEN, "Account not allowed"),
    AUTH_UNAUTHENTICATED("auth/unauthenticated", HttpStatus.UNAUTHORIZED, "Not signed in"),
    AUTH_FORBIDDEN("auth/forbidden", HttpStatus.FORBIDDEN, "Forbidden"),
    ;

    companion object {
        val allSlugs: List<String> = entries.map { it.slug }
    }
}