package ph.anevaino.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SessionAuthenticator {
    private val securityContextRepository = HttpSessionSecurityContextRepository()

    fun startSession(userId: UUID, request: HttpServletRequest, response: HttpServletResponse) {
        if (request.getSession(false) != null) {
            request.changeSessionId()
        } else {
            request.getSession(true)
        }
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken(
                AnevainoPrincipal(userId),
                null,
                AuthorityUtils.createAuthorityList("ROLE_USER"),
            )
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)
    }

    fun endSession(request: HttpServletRequest) {
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
    }
}