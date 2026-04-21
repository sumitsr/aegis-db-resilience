package io.aegis.db.resilience.web;

import io.aegis.db.resilience.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;

/**
 * Maps the domain exception hierarchy to RFC 7807 {@link ProblemDetail} responses.
 *
 * <p>Security invariants:
 * <ul>
 *   <li>No SQL text, constraint names, schema names, or stack traces reach the client.
 *   <li>The {@code traceId} field gives support teams a log-correlation handle without
 *       revealing internals.
 *   <li>{@code errorCode} is a stable machine-readable token; consumers may use it to
 *       decide retry behaviour without parsing the human-readable detail string.
 * </ul>
 */
@RestControllerAdvice
public class DatabaseExceptionAdvice {

    private static final String TYPE_BASE = "https://aegis.io/problems/db/";

    @ExceptionHandler(DataNotFoundException.class)
    public ProblemDetail handleNotFound(DataNotFoundException ex, WebRequest request) {
        return build(ex, HttpStatus.NOT_FOUND, "not-found",
                "The requested resource was not found.", request);
    }

    @ExceptionHandler(DataConflictException.class)
    public ProblemDetail handleConflict(DataConflictException ex, WebRequest request) {
        return build(ex, HttpStatus.CONFLICT, "conflict",
                "Concurrent modification conflict. Re-read the resource and retry.", request);
    }

    @ExceptionHandler(DataIntegrityException.class)
    public ProblemDetail handleIntegrity(DataIntegrityException ex, WebRequest request) {
        String detail = switch (ex.violationType()) {
            case UNIQUE      -> "A resource with these values already exists.";
            case FOREIGN_KEY -> "A referenced resource does not exist or is in use.";
            case NOT_NULL    -> "A required field is missing.";
            case CHECK       -> "A field value violates a business rule.";
            case EXCLUSION   -> "An exclusion constraint was violated.";
            case GENERIC     -> "A data integrity constraint was violated.";
        };
        return build(ex, HttpStatus.CONFLICT, "integrity-violation", detail, request);
    }

    @ExceptionHandler({DataTimeoutException.class})
    public ProblemDetail handleTimeout(DataTimeoutException ex, WebRequest request) {
        return build(ex, HttpStatus.GATEWAY_TIMEOUT, "timeout",
                "The operation timed out. Please try again.", request);
    }

    @ExceptionHandler(DataUnavailableException.class)
    public ProblemDetail handleUnavailable(DataUnavailableException ex, WebRequest request) {
        return build(ex, HttpStatus.SERVICE_UNAVAILABLE, "unavailable",
                "The database is temporarily unavailable. Please try again shortly.", request);
    }

    @ExceptionHandler(TransientDataOperationException.class)
    public ProblemDetail handleTransient(TransientDataOperationException ex, WebRequest request) {
        // Reached the controller only if retries are exhausted
        return build(ex, HttpStatus.SERVICE_UNAVAILABLE, "transient-failure",
                "A transient database error occurred. Please retry.", request);
    }

    @ExceptionHandler(DataAccessProgrammingException.class)
    public ProblemDetail handleProgrammingError(DataAccessProgrammingException ex, WebRequest request) {
        // Never expose internal details for programming errors
        return build(ex, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "An internal error occurred. Support has been notified.", request);
    }

    // -------------------------------------------------------------------------

    private ProblemDetail build(
            DataOperationException ex,
            HttpStatus status,
            String slug,
            String safeDetail,
            WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, safeDetail);
        problem.setType(URI.create(TYPE_BASE + slug));
        problem.setTitle(toTitle(slug));
        problem.setProperty("errorCode",  slug.toUpperCase().replace('-', '_'));
        problem.setProperty("traceId",    currentTraceId());
        problem.setProperty("repository", ex.repository());
        problem.setProperty("operation",  ex.operation());
        // sqlState intentionally omitted — server-side log carries it
        return problem;
    }

    private String toTitle(String slug) {
        return Character.toUpperCase(slug.charAt(0))
                + slug.substring(1).replace('-', ' ');
    }

    private String currentTraceId() {
        // MDC key populated by OTel/Brave bridge; graceful fallback
        String traceId = org.slf4j.MDC.get("traceId");
        return traceId != null ? traceId : "none";
    }
}
