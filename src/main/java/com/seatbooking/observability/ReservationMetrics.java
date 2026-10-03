package com.seatbooking.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Burst counters: reservations_confirmed_total and reservations_declined_total{reason}.
 * Every reason is registered up front so it reports 0 rather than being absent before the first decline.
 */
@Component
public class ReservationMetrics {

    public enum DeclineReason {
        SEAT_TAKEN,
        PER_USER_LIMIT,
        IDEMPOTENT_REPLAY,
        KEY_REUSED;

        private String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Counter confirmed;
    private final Map<DeclineReason, Counter> declined = new EnumMap<>(DeclineReason.class);

    public ReservationMetrics(MeterRegistry registry) {
        this.confirmed = Counter.builder("reservations.confirmed")
                .description("Reservations confirmed")
                .register(registry);
        for (DeclineReason reason : DeclineReason.values()) {
            declined.put(reason, Counter.builder("reservations.declined")
                    .description("Reservation requests that did not reserve anything new")
                    .tag("reason", reason.tag())
                    .register(registry));
        }
    }

    public void recordConfirmed() {
        confirmed.increment();
    }

    public void recordDeclined(DeclineReason reason) {
        declined.get(reason).increment();
    }
}
