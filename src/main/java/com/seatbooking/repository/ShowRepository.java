package com.seatbooking.repository;

import com.seatbooking.model.Show;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Repository pattern: all SQL against the shows table lives here. */
@Repository
@RequiredArgsConstructor
public class ShowRepository {

    private static final String INSERT_SHOW = """
            INSERT INTO shows (name, price_paise, per_user_limit, total_seats)
            VALUES (?, ?, ?, ?)
            RETURNING id
            """;

    private static final String SELECT_SHOW = """
            SELECT id, name, price_paise, per_user_limit, total_seats
            FROM shows
            WHERE id = ?
            """;

    private final JdbcClient jdbc;

    public Show insertShow(String name, long pricePaise, int perUserLimit, int totalSeats) {
        UUID id = jdbc.sql(INSERT_SHOW)
                .params(name, pricePaise, perUserLimit, totalSeats)
                .query(UUID.class)
                .single();
        return new Show(id, name, pricePaise, perUserLimit, totalSeats);
    }

    public Optional<Show> findShow(UUID showId) {
        return jdbc.sql(SELECT_SHOW)
                .param(showId)
                .query((rs, rowNum) -> new Show(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getLong("price_paise"),
                        rs.getInt("per_user_limit"),
                        rs.getInt("total_seats")))
                .optional();
    }
}
