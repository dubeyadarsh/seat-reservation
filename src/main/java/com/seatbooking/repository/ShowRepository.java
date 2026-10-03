package com.seatbooking.repository;

import com.seatbooking.model.Show;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Repository pattern: all SQL for shows and their seats lives here. */
@Repository
@RequiredArgsConstructor
public class ShowRepository {

    private static final String INSERT_SHOW = """
            INSERT INTO shows (name, price_paise, per_user_limit, total_seats)
            VALUES (?, ?, ?, ?)
            RETURNING id
            """;

    /** One statement for all seats; position is the label's index in the request (0-based). */
    private static final String INSERT_SEATS = """
            INSERT INTO seats (show_id, seat_label, position)
            SELECT ?, label, ordinality - 1
            FROM unnest(?::text[]) WITH ORDINALITY AS t(label, ordinality)
            """;

    private final JdbcClient jdbc;

    public Show insertShow(String name, long pricePaise, int perUserLimit, int totalSeats) {
        UUID id = jdbc.sql(INSERT_SHOW)
                .params(name, pricePaise, perUserLimit, totalSeats)
                .query(UUID.class)
                .single();
        return new Show(id, name, pricePaise, perUserLimit, totalSeats);
    }

    public void insertSeats(UUID showId, List<String> labels) {
        jdbc.sql(INSERT_SEATS)
                .params(showId, labels.toArray(String[]::new))
                .update();
    }
}
