package com.seatbooking.dto.reservation;

import com.seatbooking.model.Reservation;
import com.seatbooking.model.ReservationStatus;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID showId,
        String userId,
        List<String> seats,
        long amountPaise,
        ReservationStatus status) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.id(),
                reservation.showId(),
                reservation.userId(),
                reservation.seatLabels(),
                reservation.amountPaise(),
                reservation.status());
    }
}
