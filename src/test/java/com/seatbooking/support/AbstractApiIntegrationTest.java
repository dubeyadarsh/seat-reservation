package com.seatbooking.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatbooking.security.JwtService;
import com.seatbooking.security.Role;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Shared HTTP helpers so reservation tests read as scenarios rather than request plumbing. */
@AutoConfigureMockMvc
public abstract class AbstractApiIntegrationTest extends AbstractPostgresIntegrationTest {

    protected static final MediaType JSON = MediaType.APPLICATION_JSON;
    protected static final long PRICE_PAISE = 25_000L;

    /**
     * One embedded database is shared by every test, and idempotency keys are unique per user for
     * all time. JUnit builds a new instance per test method, so this lets scenarios reuse readable
     * keys like "k1" without colliding with the same key in another test.
     */
    private final String keyScope = "-" + UUID.randomUUID();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    protected UUID createShow(String... seatLabels) throws Exception {
        return createShow(null, seatLabels);
    }

    protected UUID createShow(Integer perUserLimit, String... seatLabels) throws Exception {
        String limitField = perUserLimit == null ? "" : ",\"per_user_limit\":" + perUserLimit;
        String body = "{\"name\":\"test-show\",\"seats\":[%s],\"price_paise\":%d%s}"
                .formatted(quoted(seatLabels), PRICE_PAISE, limitField);

        String json = mockMvc.perform(authed(post("/shows"), adminToken()).contentType(JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("id").asText());
    }

    protected ResultActions reserve(UUID showId, String token, String body) throws Exception {
        return mockMvc.perform(authed(post("/shows/{id}/reserve", showId), token).contentType(JSON).content(body));
    }

    protected ResultActions cancel(String reservationId, String token) throws Exception {
        return mockMvc.perform(authed(post("/reservations/{id}/cancel", reservationId), token));
    }

    protected String body(String idempotencyKey, String... seatLabels) {
        return "{\"seats\":[%s],\"idempotency_key\":\"%s\"}".formatted(quoted(seatLabels), key(idempotencyKey));
    }

    protected String key(String idempotencyKey) {
        return idempotencyKey + keyScope;
    }

    protected String reservationId(String reserveResponseJson) throws Exception {
        return objectMapper.readTree(reserveResponseJson).get("reservation_id").asText();
    }

    protected String userToken(String userId) {
        return jwtService.issue(userId, EnumSet.of(Role.USER)).value();
    }

    protected String adminToken() {
        return jwtService.issue("admin", EnumSet.of(Role.USER, Role.ADMIN)).value();
    }

    protected static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, String token) {
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static String quoted(String... values) {
        return List.of(values).stream().collect(Collectors.joining("\",\"", "\"", "\""));
    }
}
