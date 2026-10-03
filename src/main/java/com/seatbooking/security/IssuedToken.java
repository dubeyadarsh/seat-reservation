package com.seatbooking.security;

import java.time.Instant;

public record IssuedToken(String value, Instant expiresAt) {
}
