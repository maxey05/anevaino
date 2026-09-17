package ph.anevaino.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class SecurityHeadersConfig : OncePerRequestFilter() {
    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        res.setHeader(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; img-src 'self' data:; frame-ancestors 'none'",
        )
        res.setHeader("X-Content-Type-Options", "nosniff")
        res.setHeader("Referrer-Policy", "no-referrer")
        chain.doFilter(req, res)
    }
}