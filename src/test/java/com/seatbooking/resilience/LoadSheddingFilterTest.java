package com.seatbooking.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatbooking.config.LoadSheddingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoadSheddingFilterTest {

    private static final Duration QUEUE_TIMEOUT = Duration.ofMillis(100);
    private static final long WAIT_SECONDS = 5;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LoadSheddingFilter filter = new LoadSheddingFilter(
            new LoadSheddingProperties(1, QUEUE_TIMEOUT, Duration.ofSeconds(2)), new ObjectMapper(), registry);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void requestThatCannotGetASlotInTimeIsAnswered429WithRetryAfter() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> occupant = executor.submit(() -> run("/shows/x/reserve", (req, res) -> {
            inside.countDown();
            await(release);
        }));
        assertThat(inside.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        MockHttpServletResponse rejected = run("/shows/x/reserve", (req, res) -> { });

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(rejected.getContentAsString()).contains("\"error\":\"server_busy\"");
        assertThat(registry.get("http.requests.shed").counter().count()).isEqualTo(1);

        release.countDown();
        occupant.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(run("/shows/x/reserve", (req, res) -> { }).getStatus()).isEqualTo(200);
    }

    @Test
    void healthProbesAndMetricsBypassTheQueueEvenWhenItIsFull() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(() -> run("/shows/x/reserve", (req, res) -> {
            inside.countDown();
            await(release);
        }));
        assertThat(inside.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThat(run("/health/readiness", (req, res) -> { }).getStatus()).isEqualTo(200);
        assertThat(run("/metrics", (req, res) -> { }).getStatus()).isEqualTo(200);
        release.countDown();
    }

    @Test
    void slotIsReturnedEvenWhenTheRequestFails() throws Exception {
        assertThatThrownBy(() -> run("/shows/x/reserve", (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);

        assertThat(run("/shows/x/reserve", (req, res) -> { }).getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse run(String path, FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
