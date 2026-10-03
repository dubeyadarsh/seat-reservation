package com.seatbooking.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatbooking.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AuthControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ADMIN_SECRET = "test-admin-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void issuesUserTokenThatAuthenticatesMe() throws Exception {
        String token = issueToken("{\"user_id\":\"alice\"}", null);

        mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value("alice"))
                .andExpect(jsonPath("$.roles", containsInAnyOrder("USER")));
    }

    @Test
    void issuesAdminTokenOnlyWithSecret() throws Exception {
        mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"ops\",\"role\":\"admin\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        String token = issueToken("{\"user_id\":\"ops\",\"role\":\"admin\"}", ADMIN_SECRET);
        mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.roles", containsInAnyOrder("USER", "ADMIN")));
    }

    @Test
    void rejectsInvalidUserIdWithFieldError() throws Exception {
        mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"bad id with spaces\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.field_errors.user_id", notNullValue()))
                .andExpect(jsonPath("$.request_id", notNullValue()));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_request"));
    }

    @Test
    void meWithoutTokenIs401() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void tamperedTokenIs401() throws Exception {
        String token = issueToken("{\"user_id\":\"alice\"}", null);
        String tampered = token.substring(0, token.length() - 2) + "xx";

        mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void echoesSafeRequestIdAndReplacesUnsafeOne() throws Exception {
        mockMvc.perform(get("/auth/me").header("X-Request-Id", "trace-123"))
                .andExpect(header().string("X-Request-Id", "trace-123"))
                .andExpect(jsonPath("$.request_id").value("trace-123"));

        mockMvc.perform(get("/auth/me").header("X-Request-Id", "bad id\ninjected"))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.not("bad id\ninjected")));
    }

    @Test
    void setsSecurityHeaders() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    private String issueToken(String body, String adminSecret) throws Exception {
        var request = post("/auth/token").contentType(MediaType.APPLICATION_JSON).content(body);
        if (adminSecret != null) {
            request.header("X-Admin-Secret", adminSecret);
        }
        String json = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(json);
        return node.get("access_token").asText();
    }
}
