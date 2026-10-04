package com.seatbooking.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from {@code app.cache.*}. Every cache is size-bounded so memory stays flat however many shows exist.
 *
 * @param soldSeatTtl        upper bound on how long another instance may keep declining a seat after a cancel
 * @param soldSeatMaxEntries confirmed seats remembered for the in-memory decline fast path
 * @param showMaxEntries     shows kept in memory; shows are immutable, so entries never go stale
 * @param verifiedTokenMaxEntries tokens whose signature was already checked; each is still re-checked for expiry
 * @param usedKeyMaxEntries  idempotency keys known to be stored, so their reuse always reaches the replay check
 */
@Validated
@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(
        @NotNull Duration soldSeatTtl,
        @Min(1) long soldSeatMaxEntries,
        @Min(1) long showMaxEntries,
        @Min(1) long verifiedTokenMaxEntries,
        @Min(1) long usedKeyMaxEntries) {
}
