package com.seatbooking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.seatbooking.support.AbstractApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The correctness bar: a stampede must produce exactly one winner, clean declines and no 5xx. */
class ReservationConcurrencyIntegrationTest extends AbstractApiIntegrationTest {

    private static final int THREADS = 16;
    private static final int ATTEMPTS = 40;
    private static final Integer CREATED = 201;
    private static final Integer OK = 200;
    private static final Integer CONFLICT = 409;

    @Test
    void hotSeatStormHasExactlyOneWinnerAndNoServerErrors() throws Exception {
        UUID showId = createShow("A12");

        List<Integer> statuses = fireInParallel(ATTEMPTS, attempt ->
                status(reserve(showId, userToken("user" + attempt), body("key" + attempt, "A12"))));

        assertThat(statuses).filteredOn(CREATED::equals).hasSize(1);
        assertThat(statuses).filteredOn(CONFLICT::equals).hasSize(ATTEMPTS - 1);
        assertThat(statuses).allMatch(code -> code < 500);
        expectCounts(showId, 0, 1);
    }

    @Test
    void overlappingMultiSeatRequestsNeverDeadlock() throws Exception {
        UUID showId = createShow("A1", "A2", "A3", "A4");

        // Half request the pair in one order and half in the other: without ordered locking this deadlocks.
        List<Integer> statuses = fireInParallel(ATTEMPTS, attempt -> {
            String[] seats = attempt % 2 == 0 ? new String[] {"A1", "A2"} : new String[] {"A2", "A1"};
            return status(reserve(showId, userToken("user" + attempt), body("key" + attempt, seats)));
        });

        assertThat(statuses).allMatch(code -> code < 500);
        assertThat(statuses).filteredOn(CREATED::equals).hasSize(1);
        expectCounts(showId, 2, 2);
    }

    @Test
    void perUserLimitHoldsWhenOneUserFiresInParallel() throws Exception {
        UUID showId = createShow(4, "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "B1");
        String token = userToken("alice");
        List<String> seats = List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "B1");

        List<Integer> statuses = fireInParallel(seats.size(), attempt ->
                status(reserve(showId, token, body("key" + attempt, seats.get(attempt)))));

        assertThat(statuses).allMatch(code -> code < 500);
        assertThat(statuses).filteredOn(CREATED::equals).hasSize(4);
        expectCounts(showId, 6, 4);
    }

    @Test
    void parallelRetriesOfOneKeyReserveExactlyOnce() throws Exception {
        UUID showId = createShow("A1", "A2", "A3");
        String token = userToken("alice");

        List<Integer> statuses = fireInParallel(ATTEMPTS, attempt ->
                status(reserve(showId, token, body("retried-key", "A1"))));

        assertThat(statuses).filteredOn(CREATED::equals).hasSize(1);
        assertThat(statuses).filteredOn(OK::equals).hasSize(ATTEMPTS - 1);
        expectCounts(showId, 2, 1);
    }

    /** available + held + confirmed == total_seats, to the unit, after the burst. */
    private void expectCounts(UUID showId, int available, int confirmed) throws Exception {
        mockMvc.perform(get("/shows/{id}", showId))
                .andExpect(jsonPath("$.counts.available").value(available))
                .andExpect(jsonPath("$.counts.held").value(0))
                .andExpect(jsonPath("$.counts.confirmed").value(confirmed))
                .andExpect(jsonPath("$.total_seats").value(available + confirmed));
    }

    private static int status(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andReturn().getResponse().getStatus();
    }

    /** Every task waits on one gate so the requests collide as closely as the machine allows. */
    private List<Integer> fireInParallel(int attempts, Attempt task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int attempt = 0; attempt < attempts; attempt++) {
                int index = attempt;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return task.run(index);
                }));
            }
            startGate.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Attempt {
        int run(int attempt) throws Exception;
    }
}
