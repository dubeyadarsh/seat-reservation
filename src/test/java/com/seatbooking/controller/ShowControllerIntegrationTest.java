package com.seatbooking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatbooking.security.JwtService;
import com.seatbooking.security.Role;
import com.seatbooking.support.AbstractPostgresIntegrationTest;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class ShowControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String VALID_BODY =
            "{\"name\":\"friday-night\",\"seats\":[\"A1\",\"A2\",\"A3\"],\"price_paise\":25000}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void adminCreatesShowWithEverySeatAvailable() throws Exception {
        String json = createShow(VALID_BODY, adminToken())
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("/shows/")))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name").value("friday-night"))
                .andExpect(jsonPath("$.price_paise").value(25000))
                .andExpect(jsonPath("$.per_user_limit").value(4))
                .andExpect(jsonPath("$.total_seats").value(3))
                .andExpect(jsonPath("$.counts.available").value(3))
                .andExpect(jsonPath("$.counts.held").value(0))
                .andExpect(jsonPath("$.counts.confirmed").value(0))
                .andExpect(jsonPath("$.seats[*].label").value(contains("A1", "A2", "A3")))
                .andExpect(jsonPath("$.seats[*].status", everyItem(is("available"))))
                .andReturn().getResponse().getContentAsString();

        UUID showId = UUID.fromString(objectMapper.readTree(json).get("id").asText());
        List<String> storedLabels = jdbc.sql(
                        "SELECT seat_label FROM seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY position")
                .param(showId)
                .query(String.class)
                .list();
        assertThat(storedLabels).containsExactly("A1", "A2", "A3");
    }

    @Test
    void regularUserIsForbidden() throws Exception {
        createShow(VALID_BODY, token(EnumSet.of(Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void anonymousCallerIsUnauthorized() throws Exception {
        createShow(VALID_BODY, null)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidBodyReturnsFieldErrors() throws Exception {
        createShow("{\"name\":\"\",\"seats\":[],\"price_paise\":0}", adminToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.field_errors.name", notNullValue()))
                .andExpect(jsonPath("$.field_errors.seats", notNullValue()))
                .andExpect(jsonPath("$.field_errors.price_paise", notNullValue()));
    }

    @Test
    void duplicateSeatLabelsAreRejected() throws Exception {
        createShow("{\"name\":\"dup\",\"seats\":[\"A1\",\"A1\"],\"price_paise\":25000}", adminToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("duplicate_seats"));
    }

    @Test
    void fractionalPriceIsRejectedNotTruncated() throws Exception {
        createShow("{\"name\":\"float\",\"seats\":[\"A1\"],\"price_paise\":250.5}", adminToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_request"));
    }

    @Test
    void anyoneCanReadShowStateAndCountsReconcile() throws Exception {
        UUID showId = createdShowId();

        mockMvc.perform(get("/shows/{id}", showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(showId.toString()))
                .andExpect(jsonPath("$.total_seats").value(3))
                .andExpect(jsonPath("$.counts.available").value(3))
                .andExpect(jsonPath("$.counts.held").value(0))
                .andExpect(jsonPath("$.counts.confirmed").value(0))
                .andExpect(jsonPath("$.seats[*].label").value(contains("A1", "A2", "A3")))
                .andExpect(jsonPath("$.seats[*].status", everyItem(is("available"))));
    }

    @Test
    void unknownShowReturnsNotFound() throws Exception {
        mockMvc.perform(get("/shows/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void malformedShowIdIsClientErrorNotServerError() throws Exception {
        mockMvc.perform(get("/shows/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_parameter"));
    }

    private UUID createdShowId() throws Exception {
        String json = createShow(VALID_BODY, adminToken())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("id").asText());
    }

    private ResultActions createShow(String body, String token) throws Exception {
        var request = post("/shows").contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    private String adminToken() {
        return token(EnumSet.of(Role.USER, Role.ADMIN));
    }

    private String token(EnumSet<Role> roles) {
        return jwtService.issue("test-user", roles).value();
    }
}
