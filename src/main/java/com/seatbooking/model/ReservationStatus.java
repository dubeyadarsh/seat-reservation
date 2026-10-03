package com.seatbooking.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Stored as the enum name (CONFIRMED); shown in the API in lowercase (confirmed). */
public enum ReservationStatus {
    CONFIRMED,
    CANCELLED;

    @JsonValue
    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
