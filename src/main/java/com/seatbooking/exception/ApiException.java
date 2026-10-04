package com.seatbooking.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * The only custom exception. Throw it anywhere; GlobalExceptionHandler turns it into
 * {@code {"error": code, "message": message}} with the given HTTP status.
 *
 * <p>It describes an expected client outcome, never a bug, and is never logged with a trace. So no
 * stack trace is captured: in a burst almost every request ends in one, and walking 100+ frames for
 * each would be pure CPU waste.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** Domain declines (seat taken, over the per-user limit, reused key) are 409, never 5xx. */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
    }
}
