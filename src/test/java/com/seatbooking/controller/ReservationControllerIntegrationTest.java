package com.seatbooking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatbooking.support.AbstractApiIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationControllerIntegrationTest extends AbstractApiIntegrationTest {

    @Test
    void confirmsSeatsAndTakesIdentityFromTheToken() throws Exception {
        UUID showId = createShow("A1", "A2", "A3");

        reserve(showId, userToken("alice"), "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\",\"user_id\":\"mallory\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.show_id").value(showId.toString()))
                .andExpect(jsonPath("$.user_id").value("alice"))
                .andExpect(jsonPath("$.seats").value(contains("A1")))
                .andExpect(jsonPath("$.amount_paise").value(PRICE_PAISE))
                .andExpect(jsonPath("$.status").value("confirmed"));

        mockMvc.perform(get("/shows/{id}", showId))
                .andExpect(jsonPath("$.counts.available").value(2))
                .andExpect(jsonPath("$.counts.confirmed").value(1));
    }

    @Test
    void amountIsPricePerSeatTimesSeatCount() throws Exception {
        UUID showId = createShow("A1", "A2");

        reserve(showId, userToken("alice"), body("k1", "A1", "A2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount_paise").value(2 * PRICE_PAISE));
    }

    @Test
    void rejectsAnonymousCaller() throws Exception {
        UUID showId = createShow("A1");

        reserve(showId, null, body("k1", "A1")).andExpect(status().isUnauthorized());
    }

    @Test
    void requiresAnIdempotencyKey() throws Exception {
        UUID showId = createShow("A1");

        reserve(showId, userToken("alice"), "{\"seats\":[\"A1\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_idempotency_key"));
    }

    @Test
    void acceptsIdempotencyKeyFromHeader() throws Exception {
        UUID showId = createShow("A1");

        mockMvc.perform(authed(post("/shows/{id}/reserve", showId), userToken("alice"))
                        .contentType(JSON)
                        .content("{\"seats\":[\"A1\"]}")
                        .header(ReservationController.IDEMPOTENCY_KEY_HEADER, key("header-key")))
                .andExpect(status().isCreated());
    }

    @Test
    void replayingTheSameKeyReturnsTheOriginalReservation() throws Exception {
        UUID showId = createShow("A1", "A2");
        String token = userToken("alice");

        String first = reserve(showId, token, body("same-key", "A1"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String replay = reserve(showId, token, body("same-key", "A1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(reservationId(replay)).isEqualTo(reservationId(first));
        mockMvc.perform(get("/shows/{id}", showId)).andExpect(jsonPath("$.counts.confirmed").value(1));
    }

    @Test
    void sameKeyWithDifferentSeatsIsRejected() throws Exception {
        UUID showId = createShow("A1", "A2");
        String token = userToken("alice");
        reserve(showId, token, body("same-key", "A1")).andExpect(status().isCreated());

        reserve(showId, token, body("same-key", "A2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency_key_reused"));
    }

    @Test
    void differentUsersMayUseTheSameKey() throws Exception {
        UUID showId = createShow("A1", "A2");

        reserve(showId, userToken("alice"), body("shared", "A1")).andExpect(status().isCreated());
        reserve(showId, userToken("bob"), body("shared", "A2")).andExpect(status().isCreated());
    }

    @Test
    void seatAlreadyConfirmedToAnotherUserIsDeclined() throws Exception {
        UUID showId = createShow("A1");
        reserve(showId, userToken("alice"), body("k1", "A1")).andExpect(status().isCreated());

        reserve(showId, userToken("bob"), body("k2", "A1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("seat_taken"));
    }

    @Test
    void partialAvailabilityReservesNothing() throws Exception {
        UUID showId = createShow("A1", "A2");
        reserve(showId, userToken("alice"), body("k1", "A2")).andExpect(status().isCreated());

        reserve(showId, userToken("bob"), body("k2", "A1", "A2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("seat_taken"));

        mockMvc.perform(get("/shows/{id}", showId))
                .andExpect(jsonPath("$.counts.available").value(1))
                .andExpect(jsonPath("$.counts.confirmed").value(1));
    }

    @Test
    void unknownSeatLabelIsNotFound() throws Exception {
        UUID showId = createShow("A1");

        reserve(showId, userToken("alice"), body("k1", "Z9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void perUserLimitIsEnforced() throws Exception {
        UUID showId = createShow(2, "A1", "A2", "A3");
        String token = userToken("alice");
        reserve(showId, token, body("k1", "A1", "A2")).andExpect(status().isCreated());

        reserve(showId, token, body("k2", "A3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("per_user_limit"));
    }

    @Test
    void ownerCancelsAndSeatsBecomeBookableAgain() throws Exception {
        UUID showId = createShow("A1");
        String aliceId = reservationId(reserve(showId, userToken("alice"), body("k1", "A1"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        cancel(aliceId, userToken("alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));

        mockMvc.perform(get("/shows/{id}", showId)).andExpect(jsonPath("$.counts.available").value(1));
        reserve(showId, userToken("bob"), body("k2", "A1")).andExpect(status().isCreated());
    }

    @Test
    void cancellingTwiceIsHarmless() throws Exception {
        UUID showId = createShow("A1");
        String id = reservationId(reserve(showId, userToken("alice"), body("k1", "A1"))
                .andReturn().getResponse().getContentAsString());

        cancel(id, userToken("alice")).andExpect(status().isOk());
        cancel(id, userToken("alice")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    void anotherUserCannotCancelAndTheSeatStaysConfirmed() throws Exception {
        UUID showId = createShow("A1");
        String id = reservationId(reserve(showId, userToken("alice"), body("k1", "A1"))
                .andReturn().getResponse().getContentAsString());

        cancel(id, userToken("mallory")).andExpect(status().isNotFound());

        mockMvc.perform(get("/shows/{id}", showId)).andExpect(jsonPath("$.counts.confirmed").value(1));
    }

    @Test
    void cancelledSeatsStopCountingTowardsTheUserLimit() throws Exception {
        UUID showId = createShow(1, "A1", "A2");
        String token = userToken("alice");
        String id = reservationId(reserve(showId, token, body("k1", "A1"))
                .andReturn().getResponse().getContentAsString());
        reserve(showId, token, body("k2", "A2")).andExpect(status().isConflict());

        cancel(id, token).andExpect(status().isOk());

        reserve(showId, token, body("k3", "A2")).andExpect(status().isCreated());
    }
}
