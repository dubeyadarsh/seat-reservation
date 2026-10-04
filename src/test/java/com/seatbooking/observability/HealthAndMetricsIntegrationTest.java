package com.seatbooking.observability;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatbooking.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Spring Boot test slices disable metrics export; {@code @AutoConfigureObservability} turns Prometheus back on. */
@AutoConfigureMockMvc
@AutoConfigureObservability
class HealthAndMetricsIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void livenessIsPublicAndUp() throws Exception {
        mockMvc.perform(get("/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readinessIsPublicAndUpWhenDatabaseIsReachable() throws Exception {
        mockMvc.perform(get("/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void metricsArePublicAndIncludeRequestMetrics() throws Exception {
        mockMvc.perform(get("/health/liveness")).andExpect(status().isOk());

        mockMvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http_server_requests_seconds_count")));
    }

    /** Registered at startup so the burst dashboard shows 0 rather than a missing series. */
    @Test
    void reservationMetricsArePresentBeforeAnyTraffic() throws Exception {
        mockMvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("reservations_confirmed_total")))
                .andExpect(content().string(containsString("reservations_declined_total{reason=\"seat_taken\"}")))
                .andExpect(content().string(containsString("reservations_declined_total{reason=\"per_user_limit\"}")))
                .andExpect(content().string(
                        containsString("reservations_declined_total{reason=\"idempotent_replay\"}")))
                .andExpect(content().string(containsString("seats_available")));
    }

    @Test
    void loadSheddingMetricsArePresentBeforeAnyOverload() throws Exception {
        mockMvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http_requests_shed_total")))
                .andExpect(content().string(containsString("http_requests_queued")));
    }
}
