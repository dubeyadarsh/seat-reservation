package com.seatbooking.exception;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.seatbooking.config.LoadSheddingProperties;
import com.seatbooking.security.InvalidTokenException;
import java.sql.SQLTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Turns every exception into an {@link ErrorResponse}. */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final PropertyNamingStrategies.NamingBase SNAKE_CASE =
            (PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.SNAKE_CASE;

    private final LoadSheddingProperties loadShedding;

    /** Our own errors: status and code come from the exception. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ErrorResponse.of(ex.getCode(), ex.getMessage()));
    }

    /** 401: no token, or a bad/expired token. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleUnauthenticated(AuthenticationException ex) {
        String message = ex instanceof InvalidTokenException
                ? ex.getMessage()
                : "Authentication required: send Authorization: Bearer <token>";
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of("unauthorized", message));
    }

    /** 403: logged in, but the role is not allowed (e.g. a USER calling an ADMIN endpoint). */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("forbidden", "You do not have permission for this action"));
    }

    /** @Valid failed: 400 with one message per field, named as the client sent it (user_id). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(SNAKE_CASE.translate(error.getField()), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(ErrorResponse.withFieldErrors("Request validation failed", fieldErrors));
    }

    /**
     * Lock timeout or deadlock loser. Ordered seat locking should prevent these, but a burst must
     * never answer 5xx, so a transient conflict is reported as the domain decline it really is.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrencyFailure(ConcurrencyFailureException ex) {
        log.warn("Concurrency failure while reserving", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("seat_taken", "Seat selection conflicted; please retry"));
    }

    /**
     * Statement or lock timeout, or similar transient database trouble: the request was too slow under
     * load, not wrong. 429 with Retry-After tells the client to come back; nothing was committed.
     */
    @ExceptionHandler(TransientDataAccessException.class)
    public ResponseEntity<ErrorResponse> handleTransientDataAccess(TransientDataAccessException ex) {
        log.warn("Transient database failure, answering busy", kv("cause", ex.getMostSpecificCause().toString()));
        return busy();
    }

    /**
     * No pooled connection within the timeout means the instance is saturated: back-pressure (429).
     * Failing to open a connection at all means the database is down: 503, and readiness fails too.
     */
    @ExceptionHandler(CannotGetJdbcConnectionException.class)
    public ResponseEntity<ErrorResponse> handleNoConnection(CannotGetJdbcConnectionException ex) {
        if (ex.getMostSpecificCause() instanceof SQLTransientConnectionException) {
            log.warn("Connection pool exhausted, answering busy");
            return busy();
        }
        log.error("Database unavailable", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of("database_unavailable", "The database is unavailable; retry shortly"));
    }

    /** A path or query value that cannot be converted, such as a show id that is not a UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("invalid_parameter", SNAKE_CASE.translate(ex.getName()) + " is not valid"));
    }

    /** Body is missing or not valid JSON. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleBadJson(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("malformed_request", "Request body is missing or not valid JSON"));
    }

    /**
     * Everything else. Spring's own errors (unknown URL, wrong method, missing header...) already
     * know their status, so we keep it; anything truly unexpected is a 500 and gets logged.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        if (ex instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            return ResponseEntity.status(status).body(ErrorResponse.of(errorCodeFor(status), ex.getMessage()));
        }
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError().body(ErrorResponse.of("internal_error", "An unexpected error occurred"));
    }

    private ResponseEntity<ErrorResponse> busy() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, loadShedding.retryAfterSeconds())
                .body(ErrorResponse.serverBusy());
    }

    /** 404 becomes "not_found", 405 becomes "method_not_allowed", etc. */
    private static String errorCodeFor(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known == null ? "error" : known.name().toLowerCase();
    }
}
