package com.seatbooking.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from {@code app.auth.*}. Validated at startup so a missing or weak JWT secret
 * stops the app instead of silently issuing forgeable tokens.
 *
 * @param jwtSecret       HMAC-SHA256 key; HS256 needs at least 256 bits.
 * @param adminSecret     shared secret required to mint ADMIN tokens; blank disables admin tokens.
 * @param devTokenEnabled exposes POST /auth/token (stand-in for a real identity provider).
 */
@Validated
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        @NotBlank(message = "JWT_SECRET must be set")
        @Size(min = 32, message = "JWT_SECRET must be at least 32 characters")
        String jwtSecret,
        @NotNull Duration tokenTtl,
        @NotBlank String issuer,
        boolean devTokenEnabled,
        String adminSecret) {
}
