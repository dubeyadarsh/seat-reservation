package com.seatbooking.observability;

/** MDC keys; the JSON log encoder emits each as a top-level field on every log line. */
public final class MdcKeys {

    public static final String REQUEST_ID = "request_id";
    public static final String USER_ID = "user_id";

    private MdcKeys() {
    }
}
