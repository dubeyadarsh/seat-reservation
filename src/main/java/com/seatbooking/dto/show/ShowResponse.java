package com.seatbooking.dto.show;

import com.seatbooking.model.Seat;
import com.seatbooking.model.Show;
import java.util.List;
import java.util.UUID;

/** Returned by both create show and get show. */
public record ShowResponse(
        UUID id,
        String name,
        long pricePaise,
        int perUserLimit,
        int totalSeats,
        SeatCounts counts,
        List<Seat> seats) {

    public static ShowResponse from(Show show, List<Seat> seats) {
        return new ShowResponse(show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats(),
                SeatCounts.of(seats), seats);
    }
}
