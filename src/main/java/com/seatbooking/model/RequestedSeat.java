package com.seatbooking.model;

/** A seat as the reserve pre-check sees it; {@code ownerId} is null while the seat is available. */
public record RequestedSeat(String label, SeatStatus status, String ownerId) {
}
