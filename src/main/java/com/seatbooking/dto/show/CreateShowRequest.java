package com.seatbooking.dto.show;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * @param seats        seat labels in display order; must be unique within the show
 * @param perUserLimit optional; defaults to {@value #DEFAULT_PER_USER_LIMIT}
 */
public record CreateShowRequest(
        @Schema(example = "friday-night")
        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name must be at most 200 characters")
        String name,

        @NotEmpty(message = "seats must not be empty")
        @Size(max = CreateShowRequest.MAX_SEATS, message = "seats must have at most " + CreateShowRequest.MAX_SEATS + " entries")
        List<@NotNull(message = "seat label is required")
             @Pattern(regexp = "^[A-Za-z0-9-]{1,16}$",
                      message = "seat label must be 1-16 characters: letters, digits, -") String> seats,

        @Schema(example = "25000")
        @NotNull(message = "price_paise is required")
        @Positive(message = "price_paise must be greater than 0")
        Long pricePaise,

        @Positive(message = "per_user_limit must be greater than 0")
        Integer perUserLimit) {

    public static final int MAX_SEATS = 5_000;
    public static final int DEFAULT_PER_USER_LIMIT = 4;

    public int perUserLimitOrDefault() {
        return perUserLimit == null ? DEFAULT_PER_USER_LIMIT : perUserLimit;
    }
}
