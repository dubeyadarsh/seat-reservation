package com.seatbooking.model;

import java.util.List;
import java.util.UUID;

public record Reservation(
        UUID id,
        UUID showId,
        String userId,
        String requestHash,
        List<String> seatLabels,
        long amountPaise,
        ReservationStatus status) {

    public Reservation cancelled() {
        return new Reservation(id, showId, userId, requestHash, seatLabels, amountPaise,
                ReservationStatus.CANCELLED);
    }
}
