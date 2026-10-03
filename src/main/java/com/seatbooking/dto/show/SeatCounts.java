package com.seatbooking.dto.show;

import com.seatbooking.model.Seat;
import com.seatbooking.model.SeatStatus;
import java.util.List;

/** available + held + confirmed always equals the show's total_seats. */
public record SeatCounts(long available, long held, long confirmed) {

    public static SeatCounts of(List<Seat> seats) {
        return new SeatCounts(count(seats, SeatStatus.AVAILABLE), count(seats, SeatStatus.HELD),
                count(seats, SeatStatus.CONFIRMED));
    }

    private static long count(List<Seat> seats, SeatStatus status) {
        return seats.stream().filter(seat -> seat.status() == status).count();
    }
}
