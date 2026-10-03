package com.seatbooking.observability;

import com.seatbooking.model.SeatStatus;
import com.seatbooking.repository.SeatRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * seats_available / seats_held / seats_confirmed, read at scrape time so they always reconcile
 * with the API. Deliberately not tagged per show: show ids are unbounded and would grow cardinality
 * forever, and the three totals are enough to check the invariant during a burst on one show.
 */
@Component
public class SeatGaugeMetrics {

    public SeatGaugeMetrics(MeterRegistry registry, SeatRepository seatRepository) {
        for (SeatStatus status : SeatStatus.values()) {
            Gauge.builder("seats." + status.name().toLowerCase(Locale.ROOT),
                            () -> seatRepository.countByStatus(status))
                    .description("Seats currently in the " + status.apiValue() + " state")
                    .register(registry);
        }
    }
}
