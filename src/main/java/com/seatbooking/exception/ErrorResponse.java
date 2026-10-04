package com.seatbooking.exception;

import com.seatbooking.observability.MdcKeys;
import java.util.Map;
import org.slf4j.MDC;

/** Body of every error response. {@code field_errors} appears only for validation failures. */
public record ErrorResponse(String error, String message, Map<String, String> fieldErrors, String requestId) {

    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, null, MDC.get(MdcKeys.REQUEST_ID));
    }

    /** Back-pressure under overload: a 429 the client can retry, never a 5xx. */
    public static ErrorResponse serverBusy() {
        return of("server_busy", "The server is at capacity; retry after the Retry-After interval");
    }

    public static ErrorResponse withFieldErrors(String message, Map<String, String> fieldErrors) {
        return new ErrorResponse("validation_failed", message, fieldErrors, MDC.get(MdcKeys.REQUEST_ID));
    }
}
