package com.seatbooking.observability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Boots against a database that does not exist: the platform must stop routing traffic (readiness DOWN)
 * without restarting the instance (liveness UP), so it recovers on its own once the database is back.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=250",
        "spring.flyway.enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ReadinessFailsClosedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void readinessIsDownWhenTheDatabaseIsUnreachable() throws Exception {
        mockMvc.perform(get("/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }

    @Test
    void livenessStaysUpSoTheInstanceIsNotRestartedForADatabaseOutage() throws Exception {
        mockMvc.perform(get("/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
