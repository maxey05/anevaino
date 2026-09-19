package ph.anevaino.common.error

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.net.URI

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(AnevainoException::class)
    fun handleDomain(ex: AnevainoException): ResponseEntity<ProblemDetail> {
        log.warn("domain exception: {}", ex.type.slug)
        val headers = HttpHeaders()
        if (ex is RateLimited) {
            headers.set(HttpHeaders.RETRY_AFTER, ex.retryAfterSeconds.toString())
        }
        return toResponse(ex.type, ex.detail, ex.extensions, headers)
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(ex: NoResourceFoundException): ResponseEntity<ProblemDetail> {
        log.warn("domain exception: {}", ProblemType.NOT_FOUND.slug)
        return toResponse(ProblemType.NOT_FOUND, "The requested resource was not found.")
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ProblemDetail> {
        log.warn("domain exception: {}", ProblemType.VALIDATION.slug)
        val fieldErrors = ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }
        return toResponse(ProblemType.VALIDATION, "Request validation failed.", mapOf("fields" to fieldErrors))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(ex: HttpMessageNotReadableException): ResponseEntity<ProblemDetail> {
        log.warn("domain exception: {}", ProblemType.VALIDATION.slug)
        return toResponse(ProblemType.VALIDATION, "The request body could not be read.")
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ProblemDetail> {
        log.error("unexpected exception", ex)
        return toResponse(ProblemType.INTERNAL_ERROR, "An unexpected error occurred.")
    }

    private fun toResponse(
        type: ProblemType,
        detail: String,
        ext: Map<String, Any?> = emptyMap(),
        headers: HttpHeaders = HttpHeaders(),
    ): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(type.status, detail)
        body.type = URI.create(type.slug)
        body.title = type.title
        body.setProperty("requestId", MDC.get("requestId"))
        ext.forEach { (key, value) -> body.setProperty(key, value) }
        return ResponseEntity.status(type.status)
            .headers(headers)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(body)
    }
}