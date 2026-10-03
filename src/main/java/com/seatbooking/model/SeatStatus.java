package com.seatbooking.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Stored in the database as the enum name (AVAILABLE); shown in the API in lowercase (available). */
public enum SeatStatus {
    AVAILABLE,
    HELD,
    CONFIRMED;

    @JsonValue
    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
