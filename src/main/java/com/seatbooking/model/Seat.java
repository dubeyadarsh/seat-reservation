package com.seatbooking.model;

public record Seat(String label, SeatStatus status) {

    public static Seat available(String label) {
        return new Seat(label, SeatStatus.AVAILABLE);
    }
}
