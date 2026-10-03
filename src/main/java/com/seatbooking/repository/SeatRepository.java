package com.seatbooking.repository;

import com.seatbooking.model.Seat;
import com.seatbooking.model.SeatStatus;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Repository pattern: all SQL against the seats table lives here. */
@Repository
@RequiredArgsConstructor
public class SeatRepository {

    /** One statement for all seats; position is the label's index in the request (0-based). */
    private static final String INSERT_SEATS = """
            INSERT INTO seats (show_id, seat_label, position)
            SELECT ?, label, ordinality - 1
            FROM unnest(?::text[]) WITH ORDINALITY AS t(label, ordinality)
            """;

    private static final String SELECT_SEATS_BY_SHOW = """
            SELECT seat_label, status
            FROM seats
            WHERE show_id = ?
            ORDER BY position
            """;

    private static final String SELECT_REQUESTED_SEATS = """
            SELECT seat_label, status
            FROM seats
            WHERE show_id = ? AND seat_label = ANY (?::text[])
            """;

    /**
     * The atomic decision: a conditional update guarded on current state. Rows are locked in label
     * order so two multi-seat requests can never deadlock against each other, and the AVAILABLE
     * predicate is re-checked after each lock is granted, so a seat someone else just took is skipped.
     */
    private static final String CLAIM_SEATS = """
            WITH locked AS (
                SELECT seat_label
                FROM seats
                WHERE show_id = ? AND seat_label = ANY (?::text[]) AND status = 'AVAILABLE'
                ORDER BY seat_label
                FOR UPDATE
            )
            UPDATE seats s
            SET status = 'CONFIRMED', reservation_id = ?, updated_at = now()
            FROM locked l
            WHERE s.show_id = ? AND s.seat_label = l.seat_label
            RETURNING s.seat_label
            """;

    /** Scoped to the reservation, so a release can never touch a seat confirmed to someone else. */
    private static final String RELEASE_SEATS = """
            UPDATE seats
            SET status = 'AVAILABLE', reservation_id = NULL, updated_at = now()
            WHERE reservation_id = ?
            """;

    private static final String COUNT_BY_STATUS = "SELECT count(*) FROM seats WHERE status = ?";

    private final JdbcClient jdbc;

    public void insertSeats(UUID showId, List<String> labels) {
        jdbc.sql(INSERT_SEATS)
                .params(showId, toArray(labels))
                .update();
    }

    /** Seats in the order the admin listed them when creating the show. */
    public List<Seat> findSeatsByShow(UUID showId) {
        return jdbc.sql(SELECT_SEATS_BY_SHOW)
                .param(showId)
                .query(SeatRepository::mapSeat)
                .list();
    }

    /** Lock-free pre-check: lets an already-taken seat decline without queueing for row locks. */
    public List<Seat> findRequestedSeats(UUID showId, List<String> labels) {
        return jdbc.sql(SELECT_REQUESTED_SEATS)
                .params(showId, toArray(labels))
                .query(SeatRepository::mapSeat)
                .list();
    }

    /** @return the labels actually claimed; fewer than requested means another request won the race */
    public List<String> claimSeats(UUID showId, List<String> labels, UUID reservationId) {
        String[] requested = toArray(labels);
        return jdbc.sql(CLAIM_SEATS)
                .params(showId, requested, reservationId, showId)
                .query(String.class)
                .list();
    }

    public int releaseSeats(UUID reservationId) {
        return jdbc.sql(RELEASE_SEATS).param(reservationId).update();
    }

    public long countByStatus(SeatStatus status) {
        return jdbc.sql(COUNT_BY_STATUS).param(status.name()).query(Long.class).single();
    }

    private static Seat mapSeat(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Seat(rs.getString("seat_label"), SeatStatus.valueOf(rs.getString("status")));
    }

    private static String[] toArray(List<String> labels) {
        return labels.toArray(String[]::new);
    }
}
