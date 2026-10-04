package com.seatbooking.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from {@code app.load-shedding.*}.
 *
 * @param maxConcurrentRequests requests allowed to do work at once; size it to CPU, not to traffic
 * @param queueTimeout          how long an excess request may wait for a slot before it gets 429
 * @param retryAfter            Retry-After sent with every 429
 */
@Validated
@ConfigurationProperties(prefix = "app.load-shedding")
public record LoadSheddingProperties(
        @Min(1) int maxConcurrentRequests,
        @NotNull Duration queueTimeout,
        @NotNull Duration retryAfter) {

    public String retryAfterSeconds() {
        return Long.toString(Math.max(1, retryAfter.toSeconds()));
    }
}
