package com.seatbooking.dto.reservation;

/**
 * @param replayed true when an idempotency key was reused with the same body, so nothing new was
 *                 reserved and the original reservation is returned with 200 instead of 201
 */
public record ReserveOutcome(ReservationResponse reservation, boolean replayed) {

    public static ReserveOutcome created(ReservationResponse reservation) {
        return new ReserveOutcome(reservation, false);
    }

    public static ReserveOutcome replayed(ReservationResponse reservation) {
        return new ReserveOutcome(reservation, true);
    }
}
