package com.seatbooking.repository;

import com.seatbooking.model.Reservation;
import com.seatbooking.model.ReservationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Repository pattern: all SQL against the reservations table lives here. */
@Repository
@RequiredArgsConstructor
public class ReservationRepository {

    /**
     * Serialises one user's reserves for one show so the per-user-limit count cannot be read
     * concurrently by two of their own requests. Different users never contend, so a hot-seat
     * storm from many users stays fully parallel. Released automatically when the transaction ends.
     */
    private static final String LOCK_USER_SHOW = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";

    /** Exactly-once: the unique index on (user_id, idempotency_key) decides, not application logic. */
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO reservations
                (show_id, user_id, idempotency_key, request_hash, seat_labels, amount_paise, status)
            VALUES (?, ?, ?, ?, ?::text[], ?, 'CONFIRMED')
            ON CONFLICT (user_id, idempotency_key) DO NOTHING
            RETURNING id
            """;

    private static final String SELECT_COLUMNS =
            "SELECT id, show_id, user_id, request_hash, seat_labels, amount_paise, status FROM reservations ";

    private static final String SELECT_BY_KEY = SELECT_COLUMNS + "WHERE user_id = ? AND idempotency_key = ?";

    private static final String SELECT_BY_ID = SELECT_COLUMNS + "WHERE id = ?";

    /** Excludes the row just inserted for the request being evaluated. */
    private static final String COUNT_SEATS_HELD_BY_USER = """
            SELECT coalesce(sum(cardinality(seat_labels)), 0)
            FROM reservations
            WHERE show_id = ? AND user_id = ? AND status = 'CONFIRMED' AND id <> ?
            """;

    private static final String CANCEL = """
            UPDATE reservations
            SET status = 'CANCELLED'
            WHERE id = ? AND status = 'CONFIRMED'
            """;

    private final JdbcClient jdbc;

    public void lockUserShow(UUID showId, String userId) {
        jdbc.sql(LOCK_USER_SHOW).param(showId + ":" + userId).query(Object.class).single();
    }

    /** @return the new reservation id, or empty when the idempotency key was already used */
    public Optional<UUID> insertIfAbsent(
            UUID showId, String userId, String idempotencyKey, String requestHash,
            List<String> seatLabels, long amountPaise) {
        return jdbc.sql(INSERT_IF_ABSENT)
                .params(showId, userId, idempotencyKey, requestHash,
                        seatLabels.toArray(String[]::new), amountPaise)
                .query(UUID.class)
                .optional();
    }

    public Optional<Reservation> findByIdempotencyKey(String userId, String idempotencyKey) {
        return jdbc.sql(SELECT_BY_KEY).params(userId, idempotencyKey).query(ReservationRepository::map).optional();
    }

    public Optional<Reservation> findById(UUID reservationId) {
        return jdbc.sql(SELECT_BY_ID).param(reservationId).query(ReservationRepository::map).optional();
    }

    public long countSeatsHeldByUser(UUID showId, String userId, UUID excludedReservationId) {
        return jdbc.sql(COUNT_SEATS_HELD_BY_USER)
                .params(showId, userId, excludedReservationId)
                .query(Long.class)
                .single();
    }

    /** @return 1 when this call performed the cancellation, 0 when it was already cancelled */
    public int cancel(UUID reservationId) {
        return jdbc.sql(CANCEL).param(reservationId).update();
    }

    private static Reservation map(ResultSet rs, int rowNum) throws SQLException {
        return new Reservation(
                rs.getObject("id", UUID.class),
                rs.getObject("show_id", UUID.class),
                rs.getString("user_id"),
                rs.getString("request_hash").trim(),
                List.of((String[]) rs.getArray("seat_labels").getArray()),
                rs.getLong("amount_paise"),
                ReservationStatus.valueOf(rs.getString("status")));
    }
}
