package com.seatbooking.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatbooking.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class SchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final long VALID_PRICE_PAISE = 25_000L;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void flywayAppliesAllMigrations() {
        List<String> versions = jdbc.sql(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class)
                .list();

        assertThat(versions).containsExactly("1", "2");
    }

    @Test
    void rejectsReusedIdempotencyKeyForSameUser() {
        UUID showId = insertShow(VALID_PRICE_PAISE, 1);
        insertReservation(showId, "alice", "key-1");

        assertThatThrownBy(() -> insertReservation(showId, "alice", "key-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameIdempotencyKeyForDifferentUsers() {
        UUID showId = insertShow(VALID_PRICE_PAISE, 1);
        insertReservation(showId, "alice", "shared-key");

        assertThatCode(() -> insertReservation(showId, "bob", "shared-key")).doesNotThrowAnyException();
    }

    @Test
    void showDefaultsAreApplied() {
        UUID showId = insertShow(VALID_PRICE_PAISE, 2);

        Integer perUserLimit = jdbc.sql("SELECT per_user_limit FROM shows WHERE id = ?")
                .param(showId)
                .query(Integer.class)
                .single();

        assertThat(perUserLimit).isEqualTo(4);
    }

    @Test
    void rejectsNonPositivePrice() {
        assertThatThrownBy(() -> insertShow(0L, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newSeatsStartAvailable() {
        UUID showId = insertShow(VALID_PRICE_PAISE, 1);
        insertSeat(showId, "A1", 0);

        String status = jdbc.sql("SELECT status FROM seats WHERE show_id = ? AND seat_label = ?")
                .params(showId, "A1")
                .query(String.class)
                .single();

        assertThat(status).isEqualTo("AVAILABLE");
    }

    @Test
    void rejectsDuplicateSeatLabelInSameShow() {
        UUID showId = insertShow(VALID_PRICE_PAISE, 2);
        insertSeat(showId, "A1", 0);

        assertThatThrownBy(() -> insertSeat(showId, "A1", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnknownSeatStatus() {
        UUID showId = insertShow(VALID_PRICE_PAISE, 1);

        assertThatThrownBy(() -> jdbc.sql(
                                "INSERT INTO seats (show_id, seat_label, position, status) VALUES (?, ?, ?, ?)")
                        .params(showId, "A1", 0, "SOLD")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertShow(long pricePaise, int totalSeats) {
        return jdbc.sql("INSERT INTO shows (name, price_paise, total_seats) VALUES (?, ?, ?) RETURNING id")
                .params("test-show", pricePaise, totalSeats)
                .query(UUID.class)
                .single();
    }

    private void insertReservation(UUID showId, String userId, String idempotencyKey) {
        jdbc.sql("""
                        INSERT INTO reservations
                            (show_id, user_id, idempotency_key, request_hash, seat_labels, amount_paise, status)
                        VALUES (?, ?, ?, repeat('a', 64), ?::text[], ?, 'CONFIRMED')
                        """)
                .params(showId, userId, idempotencyKey, new String[] {"A1"}, VALID_PRICE_PAISE)
                .update();
    }

    private void insertSeat(UUID showId, String label, int position) {
        jdbc.sql("INSERT INTO seats (show_id, seat_label, position) VALUES (?, ?, ?)")
                .params(showId, label, position)
                .update();
    }
}
