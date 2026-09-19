package ph.anevaino.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.security.Principal
import java.util.UUID

private const val REQUEST_ID_HEADER = "X-Request-Id"
private val SAFE_REQUEST_ID = Regex("^[A-Za-z0-9-]{1,64}$")

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val incoming = req.getHeader(REQUEST_ID_HEADER)
        val id = if (incoming != null && SAFE_REQUEST_ID.matches(incoming)) incoming else UUID.randomUUID().toString()
        MDC.put("requestId", id)
        res.setHeader(REQUEST_ID_HEADER, id)

        val start = System.nanoTime()
        try {
            chain.doFilter(req, res)
        } finally {
            val pathTemplate = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String ?: "unmapped"
            val durationMs = (System.nanoTime() - start) / 1_000_000
            log.info(
                "method={} path={} status={} durationMs={} userId={}",
                req.method,
                pathTemplate,
                res.status,
                durationMs,
                currentUserId(),
            )
            MDC.remove("requestId")
        }
    }

    private fun currentUserId(): String =
        (SecurityContextHolder.getContext().authentication?.principal as? Principal)?.name ?: "anonymous"
}