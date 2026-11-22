package de.erika.platform.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Business error with a stable machine-readable [code] (Software Design §6),
 * rendered as RFC 9457 problem details.
 */
open class ErikaException(
    val code: String,
    status: HttpStatus,
    detail: String,
    cause: Throwable? = null,
) : ErrorResponseException(status, problem(status, detail, code), cause)

private fun problem(
    status: HttpStatusCode,
    detail: String?,
    code: String,
): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail).apply { setProperty(CODE, code) }

private const val CODE = "code"

/**
 * Every error response is `application/problem+json` and carries a `code`.
 * Framework errors (bad JSON, unsupported method …) get a code derived from the status.
 */
@RestControllerAdvice
class ProblemDetailsAdvice : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    // Last step before the response is written, after Spring has built the ProblemDetail body.
    override fun createResponseEntity(
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        if (body is ProblemDetail && body.properties?.containsKey(CODE) != true) {
            body.setProperty(CODE, defaultCode(statusCode))
        }
        return super.createResponseEntity(body, headers, statusCode, request)
    }

    /** Bean validation of a request body: code VALIDATION_FAILED plus the fields (never the values). */
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        ex.body.setProperty(CODE, "VALIDATION_FAILED")
        ex.body.setProperty("errors", ex.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to it.defaultMessage) })
        return super.handleMethodArgumentNotValid(ex, headers, status, request)
    }

    /** `@PreAuthorize` denied the call: the role doesn't allow it. */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, "Your role does not allow this action.", "FORBIDDEN")

    /** Anything unexpected: log it, return a generic 500 without internals. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ProblemDetail {
        log.error("Unhandled exception", ex)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", "INTERNAL_ERROR")
    }

    private fun defaultCode(status: HttpStatusCode): String = HttpStatus.resolve(status.value())?.name ?: "HTTP_${status.value()}"
}
