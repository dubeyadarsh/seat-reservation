package com.seatbooking.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatbooking.support.AbstractPostgresIntegrationTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Timeouts are enforced by PostgreSQL on every pooled connection, and both kinds surface as exceptions
 * the global handler maps to 4xx: a stuck statement becomes 429 server_busy, a lock wait 409.
 */
class DatabaseTimeoutIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final long WAIT_SECONDS = 10;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void everyPooledConnectionCarriesStatementAndLockTimeouts() {
        assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo("5s");
        assertThat(jdbc.sql("SHOW lock_timeout").query(String.class).single()).isEqualTo("3s");
    }

    @Test
    void statementOverTheTimeoutIsCancelledAsATransientFailure() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL statement_timeout = '50ms'").update();
            jdbc.sql("SELECT pg_sleep(1)").query().listOfRows();
        })).isInstanceOf(TransientDataAccessException.class);
    }

    @Test
    void lockWaitOverTheTimeoutIsAConcurrencyFailure() throws Exception {
        String lockKey = "timeout-test";
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            holder.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").param(lockKey).query().listOfRows();
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                jdbc.sql("SET LOCAL lock_timeout = '50ms'").update();
                jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").param(lockKey).query().listOfRows();
            })).isInstanceOf(ConcurrencyFailureException.class);
        } finally {
            release.countDown();
            holder.shutdown();
            holder.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
