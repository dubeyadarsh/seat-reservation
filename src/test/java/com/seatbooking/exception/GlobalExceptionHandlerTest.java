package com.seatbooking.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatbooking.config.LoadSheddingProperties;
import java.net.ConnectException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/** Overload must never surface as 5xx; only a database that is actually down may. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(
            new LoadSheddingProperties(32, Duration.ofSeconds(30), Duration.ofSeconds(2)));

    @Test
    void exhaustedConnectionPoolIsBackPressureNotAServerError() {
        ResponseEntity<ErrorResponse> response = handler.handleNoConnection(new CannotGetJdbcConnectionException(
                "no connection", new SQLTransientConnectionException("Connection is not available, request timed out")));

        assertBusy(response);
    }

    @Test
    void statementTimeoutIsBackPressureNotAServerError() {
        assertBusy(handler.handleTransientDataAccess(new QueryTimeoutException("canceling statement due to timeout")));
    }

    @Test
    void unreachableDatabaseIsReportedAsUnavailable() {
        SQLTransientConnectionException poolError = new SQLTransientConnectionException("timed out");
        poolError.initCause(new ConnectException("Connection refused"));

        ResponseEntity<ErrorResponse> response =
                handler.handleNoConnection(new CannotGetJdbcConnectionException("no connection", poolError));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error()).isEqualTo("database_unavailable");
    }

    private static void assertBusy(ResponseEntity<ErrorResponse> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(response.getBody().error()).isEqualTo("server_busy");
    }
}
