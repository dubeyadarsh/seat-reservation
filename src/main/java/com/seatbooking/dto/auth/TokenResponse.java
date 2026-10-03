package com.seatbooking.dto.auth;

import com.seatbooking.security.Role;
import java.util.Set;

public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String userId,
        Set<Role> roles) {
}
