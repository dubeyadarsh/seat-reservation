package com.seatbooking.observability;

import static net.logstash.logback.argument.StructuredArguments.kv;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One structured access-log line per request: method, path, status, duration.
 * Logs the path only (never the query string, headers or body) so tokens and secrets cannot leak.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    /** Scraped every few seconds by probes; logging them would drown real traffic. */
    private static final List<String> UNLOGGED_PATH_PREFIXES = List.of("/health", "/metrics", "/actuator");

    private static final long NANOS_PER_MILLI = 1_000_000L;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return UNLOGGED_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            logCompletion(request, response.getStatus(), (System.nanoTime() - startNanos) / NANOS_PER_MILLI);
        }
    }

    private static void logCompletion(HttpServletRequest request, int status, long durationMs) {
        Object[] fields = {
                kv("method", request.getMethod()),
                kv("path", request.getRequestURI()),
                kv("status", status),
                kv("duration_ms", durationMs)
        };
        if (HttpStatusCode.valueOf(status).is5xxServerError()) {
            log.warn("request completed", fields);
        } else {
            log.info("request completed", fields);
        }
    }
}
