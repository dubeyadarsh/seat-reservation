package com.seatbooking.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatbooking.config.LoadSheddingProperties;
import com.seatbooking.exception.ErrorResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Admission control (Bulkhead pattern). Only a fixed number of requests do work at once; the rest
 * wait in a fair FIFO queue as parked virtual threads, which cost no CPU. That keeps memory flat and
 * the CPU on requests that can finish, instead of a thousand requests each making no progress.
 *
 * <p>Runs before authentication and body parsing, so a queued request has cost almost nothing yet.
 * Probes and metrics bypass the queue: a health check stuck behind a burst would make the platform
 * restart a perfectly healthy instance. A request that cannot get a slot within the queue timeout is
 * answered 429 with Retry-After, which is honest back-pressure rather than a 5xx.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class LoadSheddingFilter extends OncePerRequestFilter {

    private static final List<String> UNQUEUED_PATH_PREFIXES = List.of("/health", "/metrics");

    private final Semaphore slots;
    private final long queueTimeoutNanos;
    private final String retryAfterSeconds;
    private final ObjectMapper objectMapper;
    private final Counter shed;

    public LoadSheddingFilter(LoadSheddingProperties properties, ObjectMapper objectMapper, MeterRegistry registry) {
        this.slots = new Semaphore(properties.maxConcurrentRequests(), true);
        this.queueTimeoutNanos = properties.queueTimeout().toNanos();
        this.retryAfterSeconds = properties.retryAfterSeconds();
        this.objectMapper = objectMapper;
        this.shed = Counter.builder("http.requests.shed")
                .description("Requests answered 429 because no slot freed up within the queue timeout")
                .register(registry);
        Gauge.builder("http.requests.queued", slots, Semaphore::getQueueLength)
                .description("Requests waiting for a slot")
                .register(registry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return UNQUEUED_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!acquireSlot()) {
            rejectAsBusy(response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            slots.release();
        }
    }

    private boolean acquireSlot() {
        try {
            return slots.tryAcquire(queueTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void rejectAsBusy(HttpServletResponse response) throws IOException {
        shed.increment();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.serverBusy());
    }
}
