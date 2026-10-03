package com.seatbooking.dto.reservation;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The user id is never read from the body; identity always comes from the bearer token.
 *
 * @param idempotencyKey optional here when the {@code Idempotency-Key} header is sent instead
 */
public record ReserveRequest(
        @ArraySchema(arraySchema = @Schema(
                description = "Seats to reserve; all are reserved or none are.",
                example = "[\"A12\"]"))
        @NotEmpty(message = "seats must not be empty")
        @Size(max = ReserveRequest.MAX_SEATS_PER_RESERVATION,
                message = "seats must have at most " + ReserveRequest.MAX_SEATS_PER_RESERVATION + " entries")
        List<@NotNull(message = "seat label is required")
             @Pattern(regexp = "^[A-Za-z0-9-]{1,16}$",
                      message = "seat label must be 1-16 characters: letters, digits, -") String> seats,

        @Schema(example = "7f3a9c12-order-1")
        @Size(max = 128, message = "idempotency_key must be at most 128 characters")
        String idempotencyKey) {

    /** A request larger than this is pointless: it would always exceed any sane per-user limit. */
    public static final int MAX_SEATS_PER_RESERVATION = 50;
}
